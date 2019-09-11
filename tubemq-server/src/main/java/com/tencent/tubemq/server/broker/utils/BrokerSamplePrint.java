/*
 * Tencent is pleased to support the open source community by making TubeMQ available.
 *
 * Copyright (C) 2012-2019 Tencent. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.tubemq.server.broker.utils;

import com.tencent.tubemq.corebase.utils.AbstractSamplePrint;
import org.slf4j.Logger;

import java.io.IOException;

/***
 * Compressed print broker's statistics.
 */
public class BrokerSamplePrint extends AbstractSamplePrint {
    private final Logger logger;

    public BrokerSamplePrint(final Logger logger) {
        super();
        this.logger = logger;
    }

    public BrokerSamplePrint(final Logger logger,
                             long sampleDetailDur, long sampleResetDur,
                             long maxDetailCount, long maxTotalCount) {
        super(sampleDetailDur, sampleResetDur, maxDetailCount, maxTotalCount);
        this.logger = logger;
    }

    @Override
    public void printExceptionCaught(Throwable e) {
        if (e != null) {
            if ((e instanceof IOException)) {
                final long now = System.currentTimeMillis();
                final long difftime = now - lastLogTime.get();
                final long curPrintCnt = totalPrintCount.incrementAndGet();
                if (curPrintCnt < maxTotalCount) {
                    if (difftime < sampleDetailDur && curPrintCnt < maxDetailCount) {
                        logger.error("[heartbeat failed] heartbeat to master exception 1 is ", e);
                    } else {
                        logger.error(sBuilder
                                .append("[heartbeat failed] heartbeat to master exception 2 is ")
                                .append(e.toString()).toString());
                        sBuilder.delete(0, sBuilder.length());
                    }
                }
                if (difftime > sampleResetDur) {
                    if (this.lastLogTime.compareAndSet(now - difftime, now)) {
                        totalPrintCount.set(0);
                    }
                }
            } else {
                final long curPrintCnt = totalUncheckCount.incrementAndGet();
                if (curPrintCnt < maxUncheckDetailCount) {
                    logger.error("[heartbeat failed] heartbeat to master exception 3 is ", e);
                } else {
                    logger.error(sBuilder
                            .append("[heartbeat failed] heartbeat to master exception 4 is ")
                            .append(e.toString()).toString());
                    sBuilder.delete(0, sBuilder.length());
                }
            }
        }
    }

    @Override
    public void printExceptionCaught(Throwable e, String hostName, String nodeName) {
        //
    }

}
