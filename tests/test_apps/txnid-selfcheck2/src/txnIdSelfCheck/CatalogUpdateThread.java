/* This file is part of VoltDB.
 * Copyright (C) 2008-2015 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package txnIdSelfCheck;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.voltdb.client.Client;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcCallException;

public class CatalogUpdateThread extends BenchmarkThread {

    public static AtomicLong progressInd = new AtomicLong(0);
    final Client client;
    final AtomicBoolean m_shouldContinue = new AtomicBoolean(true);
    final AtomicBoolean m_needsBlock = new AtomicBoolean(false);
    final String[] createOrDrop = { "create table anothertable (a int);",
                                    "drop table anothertable if exists;" };

    public CatalogUpdateThread(Client client) {
        setName("CatalogUpdateThread");
        this.client = client;
    }

    void shutdown() {
        m_shouldContinue.set(false);
        this.interrupt();
    }

    @Override
    public void run() {
        int count = 0;
        int errcnt = 0;
        while (m_shouldContinue.get()) {
            // call a ddl transaction
            log.info (createOrDrop[count]);
            try {
                ClientResponse cr = TxnId2Utils.doAdHoc(client, createOrDrop[count]);
                if (cr.getStatus() != ClientResponse.SUCCESS) {
                    log.error("Catalog update failed: " + cr.getStatusString());
                    throw new RuntimeException("stop the world");
                } else {
                    log.info("Catalog update success #" + Long.toString(progressInd.get()) + " : " + createOrDrop[count]);
                    progressInd.getAndIncrement();
                    Benchmark.txnCount.incrementAndGet();
                    errcnt = 0;
                }
            }
            catch (ProcCallException e) {
                ClientResponse cr = e.getClientResponse();
                if (cr.getStatusString().matches("Unexpected exception applying DDL statements to original catalog: DDL Error: \"object name already exists:.*")) {
                    if (errcnt > 1) {
                        log.error("too many catalog update errors");
                        throw new RuntimeException("stop the world");
                    } else
                        errcnt++;
                }
            }
            catch (Exception e) {
                log.error("CatalogUpdateThread threw an error:", e);
                throw new RuntimeException(e);
            }
            count = ++count & 1;
            try { Thread.sleep(10000); }
            catch (Exception e) {}
        }
        log.info(getName() + " thread has stopped");
    }
}
