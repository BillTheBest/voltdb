/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.iv2;

import java.util.Arrays;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import org.apache.zookeeper_voltpatches.KeeperException;
import org.apache.zookeeper_voltpatches.ZooKeeper;
import org.voltcore.messaging.HostMessenger;
import org.voltcore.utils.CoreUtils;
import org.voltcore.zk.LeaderElector;
import org.voltcore.zk.ZKUtil;
import org.voltdb.BackendTarget;
import org.voltdb.CatalogContext;
import org.voltdb.CatalogSpecificPlanner;
import org.voltdb.CommandLog;
import org.voltdb.MemoryStats;
import org.voltdb.PartitionDRGateway;
import org.voltdb.ProducerDRGateway;
import org.voltdb.Promotable;
import org.voltdb.SnapshotCompletionMonitor;
import org.voltdb.StartAction;
import org.voltdb.StatsAgent;
import org.voltdb.VoltDB;
import org.voltdb.VoltZK;
import org.voltdb.export.ExportManager;
import org.voltdb.iv2.LeaderCache.LeaderCallBackInfo;
import org.voltdb.iv2.RepairAlgo.RepairResult;
import org.voltdb.iv2.SpScheduler.DurableUniqueIdListener;
import com.google_voltpatches.common.collect.ImmutableMap;
import com.google_voltpatches.common.collect.Sets;

/**
 * Subclass of Initiator to manage single-partition operations.
 * This class is primarily used for object construction and configuration plumbing;
 * Try to avoid filling it with lots of other functionality.
 */
public class SpInitiator extends BaseInitiator implements Promotable
{
    final private LeaderCache m_leaderCache;
    private boolean m_isBalanceSPIRequested = false;
    private final TickProducer m_tickProducer;
    private final LeaderCache m_balanceSpiCache;

    LeaderCache.Callback m_leadersChangeHandler = new LeaderCache.Callback()
    {
        @Override
        public void run(ImmutableMap<Integer, LeaderCallBackInfo> cache)
        {
            String hsidStr = CoreUtils.hsIdToString(m_initiatorMailbox.getHSId());
            if (cache != null && tmLog.isDebugEnabled()) {
                tmLog.debug(hsidStr + " [SpInitiator] cache keys: " + Arrays.toString(cache.keySet().toArray()));
                tmLog.debug(hsidStr + " [SpInitiator] cache values: " + Arrays.toString(cache.values().toArray()));
            }

            Set<Long> leaders = Sets.newHashSet();
            for (Entry<Integer, LeaderCallBackInfo> entry: cache.entrySet()) {
                Long HSId = entry.getValue().m_HSID;
                leaders.add(HSId);
                if (HSId == getInitiatorHSId()) {
                    m_isBalanceSPIRequested = entry.getValue().m_isBalanceSPIRequested;
                    acceptPromotion();
                    break;
                }
            }

            //This was the leader but SPI has been migrated, so demote it.
            if ( tmLog.isDebugEnabled() && m_scheduler.isSpiBalanceRequested()
                    && !leaders.contains(getInitiatorHSId())) {
                tmLog.debug(CoreUtils.hsIdToString(getInitiatorHSId()) + " is not a leader anymore. It has been demoted!");
            }
        }
    };

    LeaderCache.Callback m_balanceSpiHandler = new LeaderCache.Callback() {
        @Override
        public void run(ImmutableMap<Integer, LeaderCallBackInfo> cache) {
            for (Entry<Integer, LeaderCallBackInfo> entry: cache.entrySet()) {
                //current master partition
                if (entry.getKey() == m_partitionId && m_scheduler.m_isLeader && entry.getValue().m_isBalanceSPIRequested) {
                    startBalanceSPI(entry.getKey(), entry.getValue().m_HSID);
                    break;
                }
            }
        }
    };

    public SpInitiator(HostMessenger messenger, Integer partition, StatsAgent agent,
            SnapshotCompletionMonitor snapMonitor,
            StartAction startAction)
    {
        super(VoltZK.iv2masters, messenger, partition,
                new SpScheduler(partition, new SiteTaskerQueue(), snapMonitor),
                "SP", agent, startAction);
        m_leaderCache = new LeaderCache(messenger.getZK(), VoltZK.iv2appointees, m_leadersChangeHandler);
        m_tickProducer = new TickProducer(m_scheduler.m_tasks);
        m_balanceSpiCache = new LeaderCache(messenger.getZK(), VoltZK.balancespi_initiator, m_balanceSpiHandler);
    }

    @Override
    public void configure(BackendTarget backend,
                          CatalogContext catalogContext,
                          String serializedCatalog,
                          CatalogSpecificPlanner csp,
                          int numberOfPartitions,
                          StartAction startAction,
                          StatsAgent agent,
                          MemoryStats memStats,
                          CommandLog cl,
                          String coreBindIds,
                          boolean hasMPDRGateway)
        throws KeeperException, InterruptedException, ExecutionException
    {
        try {
            m_leaderCache.start(true);
            m_balanceSpiCache.start(true);
        } catch (Exception e) {
            VoltDB.crashLocalVoltDB("Unable to configure SpInitiator.", true, e);
        }

        super.configureCommon(backend, catalogContext, serializedCatalog,
                csp, numberOfPartitions, startAction, agent, memStats, cl,
                coreBindIds, hasMPDRGateway);

        m_tickProducer.start();

        // add ourselves to the ephemeral node list which BabySitters will watch for this
        // partition
        LeaderElector.createParticipantNode(m_messenger.getZK(),
                LeaderElector.electionDirForPartition(VoltZK.leaders_initiators, m_partitionId),
                Long.toString(getInitiatorHSId()), null);
    }

    @Override
    public void initDRGateway(StartAction startAction, ProducerDRGateway nodeDRGateway, boolean createMpDRGateway)
    {
        // configure DR
        PartitionDRGateway drGateway = PartitionDRGateway.getInstance(m_partitionId, nodeDRGateway, startAction);
        setDurableUniqueIdListener(drGateway);

        final PartitionDRGateway mpPDRG;
        if (createMpDRGateway) {
            mpPDRG = PartitionDRGateway.getInstance(MpInitiator.MP_INIT_PID, nodeDRGateway, startAction);
            setDurableUniqueIdListener(mpPDRG);
        } else {
            mpPDRG = null;
        }

        m_scheduler.getQueue().offer(new SiteTasker.SiteTaskerRunnable() {
            @Override
            void run()
            {
                m_executionSite.setDRGateway(drGateway, mpPDRG);
            }
        });
    }

    private void startBalanceSPI(int partition, long newLeaderHSId) {

        if (tmLog.isDebugEnabled()) {
            tmLog.debug("[SpInitiator] starting Balance SPI for partition " + partition + " to " +
                    CoreUtils.hsIdToString(newLeaderHSId));
        }

        m_scheduler.setSpiBalanceRequested(true);
        m_scheduler.m_isLeader = false;
        m_repairLog.setLeaderState(false);

        // create indicators, including all hosts and MP, to be used to
        // block the leader change notifications in Cartographer.
        // Leader change notifications will force transactions to be dropped.
        Set<Integer> hosts = m_messenger.getLiveHostIds();
        hosts.add(MpInitiator.MP_INIT_PID);
        VoltZK.createSPIBalanceIndicator(m_messenger.getZK(), hosts);

        String hsidStr = ZKUtil.suffixHSIdsWithBalanceSPIRequest(newLeaderHSId);
        VoltZK.updateLeaderCacheNode(m_messenger.getZK(), VoltZK.iv2appointees,  partition, hsidStr, tmLog);
        if (tmLog.isDebugEnabled()) {
            tmLog.debug(VoltZK.debugLeadersInfo(m_messenger.getZK()));
        }
    }

    @Override
    public void acceptPromotion()
    {
        try {
            long startTime = System.currentTimeMillis();
            Boolean success = false;
            m_term = createTerm(m_messenger.getZK(),
                    m_partitionId, getInitiatorHSId(), m_initiatorMailbox,
                    m_whoami);
            m_term.start();
            while (!success) {

                // if rejoining, a promotion can not be accepted. If the rejoin is
                // in-progress, the loss of the master will terminate the rejoin
                // anyway. If the rejoin has transferred data but not left the rejoining
                // state, it will respond REJOINING to new work which will break
                // the MPI and/or be unexpected to external clients.
                if (!m_initiatorMailbox.acceptPromotion()) {
                    tmLog.error(m_whoami
                            + "rejoining site can not be promoted to leader. Terminating.");
                    VoltDB.crashLocalVoltDB("A rejoining site can not be promoted to leader.", false, null);
                    return;
                }

                // term syslogs the start of leader promotion.
                //do not engage the reprair whne the leader change is from SPI balance.
                long txnid = Long.MIN_VALUE;
                if (!m_isBalanceSPIRequested) {
                    RepairAlgo repair =
                            m_initiatorMailbox.constructRepairAlgo(m_term.getInterestingHSIds(), m_whoami);
                    try {
                        RepairResult res = repair.start().get();
                        txnid = res.m_txnId;
                        success = true;
                        m_initiatorMailbox.setLeaderState(txnid);
                    } catch (CancellationException e) {
                        success = false;
                    }
                } else {
                    success = true;
                    m_repairLog.setLeaderState(true);
                    m_scheduler.setLeaderState(true);
                }
                if (success) {
                    tmLog.info(m_whoami
                             + "finished leader promotion. Took "
                             + (System.currentTimeMillis() - startTime) + " ms.");
                    // THIS IS where map cache should be updated, not
                    // in the promotion algorithm.
                    LeaderCacheWriter iv2masters = new LeaderCache(m_messenger.getZK(),
                            m_zkMailboxNode);
                    iv2masters.put(m_partitionId, m_initiatorMailbox.getHSId());

                    if (tmLog.isDebugEnabled() && m_isBalanceSPIRequested) {
                        tmLog.debug("Site " + CoreUtils.hsIdToString(m_initiatorMailbox.getHSId()) +
                                " becomes new leader from SPI balance request.");
                    }
                }
                else {
                    // The only known reason to fail is a failed replica during
                    // recovery; that's a bounded event (by k-safety).
                    // CrashVoltDB here means one node failure causing another.
                    // Don't create a cascading failure - just try again.
                    tmLog.info(m_whoami
                            + "interrupted during leader promotion after "
                            + (System.currentTimeMillis() - startTime) + " ms. of "
                            + "trying. Retrying.");
                }
                m_scheduler.setSpiBalanceRequested(false);
            }
            // Tag along and become the export master too
            // leave the export on the former leader, now a replica
            if (!m_isBalanceSPIRequested) {
                ExportManager.instance().acceptMastership(m_partitionId);
            }
            m_isBalanceSPIRequested = false;
        } catch (Exception e) {
            VoltDB.crashLocalVoltDB("Terminally failed leader promotion.", true, e);
        }
    }

    /**
     * SpInitiator has userdata that must be rejoined.
     */
    @Override
    public boolean isRejoinable()
    {
        return true;
    }

    @Override
    public Term createTerm(ZooKeeper zk, int partitionId, long initiatorHSId, InitiatorMailbox mailbox,
            String whoami)
    {
        return new SpTerm(zk, partitionId, initiatorHSId, mailbox, whoami);
    }

    @Override
    public void enableWritingIv2FaultLog() {
        m_initiatorMailbox.enableWritingIv2FaultLog();
    }

    @Override
    public void setDurableUniqueIdListener(DurableUniqueIdListener listener)
    {
        m_scheduler.setDurableUniqueIdListener(listener);
    }

    @Override
    public void shutdown() {
        try {
            m_leaderCache.shutdown();
            m_balanceSpiCache.shutdown();
        } catch (InterruptedException e) {
            tmLog.info("Interrupted during shutdown", e);
        }
        super.shutdown();
    }
}
