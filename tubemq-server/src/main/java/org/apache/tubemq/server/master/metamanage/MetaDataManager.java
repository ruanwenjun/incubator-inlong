/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tubemq.server.master.metamanage;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.tubemq.corebase.TBaseConstants;
import org.apache.tubemq.corebase.TErrCodeConstants;
import org.apache.tubemq.corebase.TokenConstants;
import org.apache.tubemq.corebase.cluster.TopicInfo;
import org.apache.tubemq.corebase.utils.KeyBuilderUtils;
import org.apache.tubemq.corebase.utils.TStringUtils;
import org.apache.tubemq.corebase.utils.Tuple2;
import org.apache.tubemq.server.Server;
import org.apache.tubemq.server.common.TServerConstants;
import org.apache.tubemq.server.common.TStatusConstants;
import org.apache.tubemq.server.common.fileconfig.MasterReplicationConfig;
import org.apache.tubemq.server.common.statusdef.ManageStatus;
import org.apache.tubemq.server.common.statusdef.TopicStatus;
import org.apache.tubemq.server.common.utils.ProcessResult;
import org.apache.tubemq.server.master.bdbstore.MasterGroupStatus;
import org.apache.tubemq.server.master.metamanage.metastore.BdbMetaStoreServiceImpl;
import org.apache.tubemq.server.master.metamanage.metastore.MetaStoreService;
import org.apache.tubemq.server.master.metamanage.metastore.dao.entity.BaseEntity;
import org.apache.tubemq.server.master.metamanage.metastore.dao.entity.BrokerConfEntity;
import org.apache.tubemq.server.master.metamanage.metastore.dao.entity.ClusterSettingEntity;
import org.apache.tubemq.server.master.metamanage.metastore.dao.entity.GroupBlackListEntity;
import org.apache.tubemq.server.master.metamanage.metastore.dao.entity.GroupConsumeCtrlEntity;
import org.apache.tubemq.server.master.metamanage.metastore.dao.entity.GroupResCtrlEntity;
import org.apache.tubemq.server.master.metamanage.metastore.dao.entity.TopicCtrlEntity;
import org.apache.tubemq.server.master.metamanage.metastore.dao.entity.TopicDeployEntity;
import org.apache.tubemq.server.master.metamanage.metastore.dao.entity.TopicPropGroup;
import org.apache.tubemq.server.master.nodemanage.nodebroker.BrokerSyncStatusInfo;
import org.apache.tubemq.server.master.nodemanage.nodebroker.TargetValidResult;
import org.apache.tubemq.server.master.web.handler.BrokerProcessResult;
import org.apache.tubemq.server.master.web.handler.GroupProcessResult;
import org.apache.tubemq.server.master.web.handler.TopicProcessResult;
import org.apache.tubemq.server.master.web.model.ClusterGroupVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class MetaDataManager implements Server {

    private static final Logger logger =
            LoggerFactory.getLogger(MetaDataManager.class);
    private static final ClusterSettingEntity defClusterSetting =
            new ClusterSettingEntity().fillDefaultValue();
    private final MasterReplicationConfig replicationConfig;
    private final ScheduledExecutorService scheduledExecutorService;
    private final ConcurrentHashMap<Integer, String> brokersMap =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> brokersTLSMap =
            new ConcurrentHashMap<>();

    private final MasterGroupStatus masterGroupStatus = new MasterGroupStatus();

    private ConcurrentHashMap<Integer/* brokerId */, BrokerSyncStatusInfo> brokerRunSyncManageMap =
            new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer/* brokerId */, ConcurrentHashMap<String/* topicName */, TopicInfo>>
            brokerRunTopicInfoStoreMap = new ConcurrentHashMap<>();
    private volatile boolean isStarted = false;
    private volatile boolean isStopped = false;
    private MetaStoreService metaStoreService;
    private AtomicLong brokerInfoCheckSum = new AtomicLong(System.currentTimeMillis());
    private long lastBrokerUpdatedTime = System.currentTimeMillis();
    private long serviceStartTime = System.currentTimeMillis();


    public MetaDataManager(String nodeHost, String metaDataPath,
                           MasterReplicationConfig replicationConfig) {
        this.replicationConfig = replicationConfig;
        this.metaStoreService =
                new BdbMetaStoreServiceImpl(nodeHost, metaDataPath, replicationConfig);

        this.scheduledExecutorService =
                Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "Master Status Check");
                    }
                });
    }

    @Override
    public void start() throws Exception {
        if (isStarted) {
            return;
        }
        // start meta store service
        this.metaStoreService.start();
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    MasterGroupStatus tmpGroupStatus =
                            metaStoreService.getMasterGroupStatus(true);
                    if (tmpGroupStatus == null) {
                        masterGroupStatus.setMasterGroupStatus(false, false, false);
                    } else {
                        masterGroupStatus.setMasterGroupStatus(metaStoreService.isMasterNow(),
                                tmpGroupStatus.isWritable(), tmpGroupStatus.isReadable());
                    }
                } catch (Throwable e) {
                    logger.error(new StringBuilder(512)
                            .append("BDBGroupStatus Check exception, wait ")
                            .append(replicationConfig.getRepStatusCheckTimeoutMs())
                            .append(" ms to try again.").append(e.getMessage()).toString());
                }
            }
        }, 0, replicationConfig.getRepStatusCheckTimeoutMs(), TimeUnit.MILLISECONDS);
        // initial running data
        StringBuilder sBuffer = new StringBuilder(TBaseConstants.BUILDER_DEFAULT_SIZE);
        Map<Integer, BrokerConfEntity> curBrokerConfInfo =
                this.metaStoreService.getBrokerConfInfo(null);
        for (BrokerConfEntity entity : curBrokerConfInfo.values()) {
            updateBrokerMaps(entity);
            if (entity.getManageStatus().isApplied()) {
                boolean needFastStart = false;
                BrokerSyncStatusInfo brokerSyncStatusInfo =
                        this.brokerRunSyncManageMap.get(entity.getBrokerId());
                List<String> brokerTopicSetConfInfo = getBrokerTopicStrConfigInfo(entity, sBuffer);
                if (brokerSyncStatusInfo == null) {
                    brokerSyncStatusInfo =
                            new BrokerSyncStatusInfo(entity, brokerTopicSetConfInfo);
                    BrokerSyncStatusInfo tmpBrokerSyncStatusInfo =
                            brokerRunSyncManageMap.putIfAbsent(entity.getBrokerId(),
                                    brokerSyncStatusInfo);
                    if (tmpBrokerSyncStatusInfo != null) {
                        brokerSyncStatusInfo = tmpBrokerSyncStatusInfo;
                    }
                }
                if (brokerTopicSetConfInfo.isEmpty()) {
                    needFastStart = true;
                }
                brokerSyncStatusInfo.setFastStart(needFastStart);
                brokerSyncStatusInfo.updateCurrBrokerConfInfo(entity.getManageStatus().getCode(),
                        entity.isConfDataUpdated(), entity.isBrokerLoaded(),
                        entity.getBrokerDefaultConfInfo(), brokerTopicSetConfInfo, false);
            }
        }
        isStarted = true;
        serviceStartTime = System.currentTimeMillis();
        logger.info("BrokerConfManager StoreService Started");
    }

    @Override
    public void stop() throws Exception {
        if (isStopped) {
            return;
        }
        this.scheduledExecutorService.shutdownNow();
        isStopped = true;
        logger.info("BrokerConfManager StoreService stopped");
    }


    /**
     * If this node is the master role
     *
     * @return true if is master role or else
     */
    public boolean isSelfMaster() {
        return metaStoreService.isMasterNow();
    }

    public boolean isPrimaryNodeActive() {
        return metaStoreService.isPrimaryNodeActive();
    }

    /**
     * Transfer master role to replica node
     *
     * @throws Exception
     */
    public void transferMaster() throws Exception {
        if (metaStoreService.isMasterNow()
                && !metaStoreService.isPrimaryNodeActive()) {
            metaStoreService.transferMaster();
        }
    }

    public void clearBrokerRunSyncManageData() {
        if (!this.isStarted
                && this.isStopped) {
            return;
        }
        this.brokerRunSyncManageMap.clear();
    }

    public InetSocketAddress getMasterAddress() {
        return metaStoreService.getMasterAddress();
    }

    public ClusterGroupVO getGroupAddressStrInfo() {
        return metaStoreService.getGroupAddressStrInfo();
    }



    public long getBrokerInfoCheckSum() {
        return this.brokerInfoCheckSum.get();
    }

    public ConcurrentHashMap<Integer, String> getBrokersMap(boolean isOverTLS) {
        if (isOverTLS) {
            return brokersTLSMap;
        } else {
            return brokersMap;
        }
    }

    /**
     * Check if consume target is authorization or not
     *
     * @param consumerId
     * @param consumerGroupName
     * @param reqTopicSet
     * @param reqTopicConditions
     * @param sb
     * @return
     */
    public TargetValidResult isConsumeTargetAuthorized(String consumerId,
                                                       String consumerGroupName,
                                                       Set<String> reqTopicSet,
                                                       Map<String, TreeSet<String>> reqTopicConditions,
                                                       final StringBuilder sb) {
        // #lizard forgives
        // check topic set
        if ((reqTopicSet == null) || (reqTopicSet.isEmpty())) {
            return new TargetValidResult(false, TErrCodeConstants.BAD_REQUEST,
                    "Request miss necessary subscribed topic data");
        }

        if ((reqTopicConditions != null) && (!reqTopicConditions.isEmpty())) {
            // check if request topic set is all in the filter topic set
            Set<String> condTopics = reqTopicConditions.keySet();
            List<String> unSetTopic = new ArrayList<>();
            for (String topic : condTopics) {
                if (!reqTopicSet.contains(topic)) {
                    unSetTopic.add(topic);
                }
            }
            if (!unSetTopic.isEmpty()) {
                TargetValidResult validResult =
                        new TargetValidResult(false, TErrCodeConstants.BAD_REQUEST,
                                sb.append("Filter's Topic not subscribed :")
                                        .append(unSetTopic).toString());
                sb.delete(0, sb.length());
                return validResult;
            }
        }
        // check if consumer group is in the blacklist
        List<String> fbdTopicList = new ArrayList<>();
        Set<String> naTopicSet =
                metaStoreService.getGrpBlkLstNATopicByGroupName(consumerGroupName);
        if (naTopicSet != null) {
            for (String topicItem : reqTopicSet) {
                if (naTopicSet.contains(topicItem)) {
                    fbdTopicList.add(topicItem);
                }
            }
        }
        if (!fbdTopicList.isEmpty()) {
            return new TargetValidResult(false, TErrCodeConstants.CONSUME_GROUP_FORBIDDEN,
                    sb.append("[unAuthorized Group] ").append(consumerId)
                            .append("'s consumerGroup in blackList by administrator, topics : ")
                            .append(fbdTopicList).toString());
        }
        // check if topic enabled authorization
        ArrayList<String> enableAuthTopicList = new ArrayList<>();
        ArrayList<String> unAuthTopicList = new ArrayList<>();
        for (String topicItem : reqTopicSet) {
            if (TStringUtils.isBlank(topicItem)) {
                continue;
            }

            TopicCtrlEntity topicEntity =
                    metaStoreService.getTopicCtrlConf(topicItem);
            if (topicEntity == null) {
                continue;
            }
            if (topicEntity.isAuthCtrlEnable()) {
                enableAuthTopicList.add(topicItem);
                //check if consumer group is allowed to consume
                GroupConsumeCtrlEntity ctrlEntity =
                        metaStoreService.getConsumeCtrlByGroupAndTopic(
                                consumerGroupName, topicItem);
                if (ctrlEntity == null) {
                    unAuthTopicList.add(topicItem);
                }
            }
        }
        if (!unAuthTopicList.isEmpty()) {
            return new TargetValidResult(false, TErrCodeConstants.CONSUME_GROUP_FORBIDDEN,
                    sb.append("[unAuthorized Group] ").append(consumerId)
                            .append("'s consumerGroup not authorized by administrator, unAuthorizedTopics : ")
                            .append(unAuthTopicList).toString());
        }
        if (enableAuthTopicList.isEmpty()) {
            return new TargetValidResult(true, 200, "Ok!");
        }
        boolean isAllowed =
                checkRestrictedTopics(consumerGroupName,
                        consumerId, enableAuthTopicList, reqTopicConditions, sb);
        if (isAllowed) {
            return new TargetValidResult(true, 200, "Ok!");
        } else {
            return new TargetValidResult(false,
                    TErrCodeConstants.CONSUME_CONTENT_FORBIDDEN, sb.toString());
        }
    }

    private boolean checkRestrictedTopics(final String groupName, final String consumerId,
                                          List<String> enableAuthTopicList,
                                          Map<String, TreeSet<String>> reqTopicConditions,
                                          final StringBuilder sb) {
        List<String> restrictedTopics = new ArrayList<>();
        Map<String, GroupConsumeCtrlEntity> authorizedFilterCondMap = new HashMap<>();
        for (String topicName : enableAuthTopicList) {
            GroupConsumeCtrlEntity ctrlEntity =
                    metaStoreService.getConsumeCtrlByGroupAndTopic(groupName, topicName);
            if (ctrlEntity != null && ctrlEntity.isEnableFilterConsume()) {
                restrictedTopics.add(topicName);
                authorizedFilterCondMap.put(topicName, ctrlEntity);
            }
        }
        if (restrictedTopics.isEmpty()) {
            return true;
        }
        boolean isAllowed = true;
        for (String tmpTopic : restrictedTopics) {
            GroupConsumeCtrlEntity ilterCondEntity =
                    authorizedFilterCondMap.get(tmpTopic);
            if (ilterCondEntity == null) {
                continue;
            }
            String allowedConds = ilterCondEntity.getFilterCondStr();
            TreeSet<String> condItemSet = reqTopicConditions.get(tmpTopic);
            if (allowedConds.length() == 2
                    && allowedConds.equals(TServerConstants.BLANK_FILTER_ITEM_STR)) {
                isAllowed = false;
                sb.append("[Restricted Group] ")
                        .append(consumerId)
                        .append(" : ").append(groupName)
                        .append(" not allowed to consume any data of topic ")
                        .append(tmpTopic);
                break;
            } else {
                if (condItemSet == null
                        || condItemSet.isEmpty()) {
                    isAllowed = false;
                    sb.append("[Restricted Group] ")
                            .append(consumerId)
                            .append(" : ").append(groupName)
                            .append(" must set the filter conditions of topic ")
                            .append(tmpTopic);
                    break;
                } else {
                    Map<String, List<String>> unAuthorizedCondMap =
                            new HashMap<>();
                    for (String item : condItemSet) {
                        if (!allowedConds.contains(sb.append(TokenConstants.ARRAY_SEP)
                                .append(item).append(TokenConstants.ARRAY_SEP).toString())) {
                            isAllowed = false;
                            List<String> unAuthConds = unAuthorizedCondMap.get(tmpTopic);
                            if (unAuthConds == null) {
                                unAuthConds = new ArrayList<>();
                                unAuthorizedCondMap.put(tmpTopic, unAuthConds);
                            }
                            unAuthConds.add(item);
                        }
                        sb.delete(0, sb.length());
                    }
                    if (!isAllowed) {
                        sb.append("[Restricted Group] ").append(consumerId)
                                .append(" : unAuthorized filter conditions ")
                                .append(unAuthorizedCondMap);
                        break;
                    }
                }
            }
        }
        return isAllowed;
    }


    // ///////////////////////////////////////////////////////////////////////////////

    /**
     * Add broker configure information
     *
     * @param sBuilder   the print information string buffer
     * @param result     the process result return
     * @return true if success otherwise false
     */
    public BrokerProcessResult addBrokerConfig(BaseEntity opInfoEntity, int brokerId,
                                               String brokerIp, int brokerPort,
                                               int brokerTlsPort, int brokerWebPort,
                                               int regionId, int groupId,
                                               ManageStatus manageStatus,
                                               TopicPropGroup topicProps,
                                               StringBuilder sBuilder,
                                               ProcessResult result) {
        BrokerConfEntity entity = new BrokerConfEntity(opInfoEntity);
        entity.setBrokerIdAndIp(brokerId, brokerIp);
        entity.updModifyInfo(opInfoEntity.getDataVerId(), brokerPort, brokerTlsPort,
                brokerWebPort, regionId, groupId, manageStatus, topicProps);
        return addBrokerConfig(entity, sBuilder, result);
    }

    public BrokerProcessResult addBrokerConfig(BrokerConfEntity entity,
                                               StringBuilder sBuilder,
                                               ProcessResult result) {
        if (metaStoreService.getBrokerConfByBrokerId(entity.getBrokerId()) != null
                || metaStoreService.getBrokerConfByBrokerIp(entity.getBrokerIp()) != null) {
            result.setFailResult(DataOpErrCode.DERR_EXISTED.getCode(),
                    DataOpErrCode.DERR_EXISTED.getDescription());
        } else {
            metaStoreService.addBrokerConf(entity, sBuilder, result);
        }
        return new BrokerProcessResult(entity.getBrokerId(), entity.getBrokerIp(), result);
    }

    /**
     * Modify broker configure information
     *
     * @param entity     the broker configure entity will be update
     * @param strBuffer  the print information string buffer
     * @param result     the process result return
     * @return true if success otherwise false
     */
    public boolean modBrokerConfig(BrokerConfEntity entity,
                                   StringBuilder strBuffer,
                                   ProcessResult result) {
        metaStoreService.updBrokerConf(entity, strBuffer, result);
        return result.isSuccess();
    }

    /**
     * Delete broker's topic configure information
     *
     * @param operator  operator
     * @param brokerId  need deleted broker id
     * @param strBuffer  the print information string buffer
     * @param result     the process result return
     * @return true if success otherwise false
     */
    public boolean delBrokerTopicConfig(String operator,
                                        int brokerId,
                                        StringBuilder strBuffer,
                                        ProcessResult result) {
        metaStoreService.delTopicConfByBrokerId(operator, brokerId, strBuffer, result);
        return result.isSuccess();
    }

    /**
     * Delete broker configure information
     *
     * @param operator  operator
     * @param brokerId  need deleted broker id
     * @param strBuffer  the print information string buffer
     * @param result     the process result return
     * @return true if success otherwise false
     */
    public boolean confDelBrokerConfig(String operator,
                                       int brokerId,
                                       StringBuilder strBuffer,
                                       ProcessResult result) {
        if (!metaStoreService.checkStoreStatus(true, result)) {
            return result.isSuccess();
        }
        // valid configure status
        if (metaStoreService.hasConfiguredTopics(brokerId)) {
            result.setFailResult(DataOpErrCode.DERR_UNCLEANED.getCode(),
                    "The broker's topic configure uncleaned!");
            return result.isSuccess();
        }
        BrokerConfEntity curEntity =
                metaStoreService.getBrokerConfByBrokerId(brokerId);
        if (curEntity == null) {
            result.setFailResult(DataOpErrCode.DERR_NOT_EXIST.getCode(),
                    "The broker configure not exist!");
            return result.isSuccess();
        }
        if (curEntity.getManageStatus().isOnlineStatus()) {
            result.setFailResult(DataOpErrCode.DERR_ILLEGAL_STATUS.getCode(),
                    "Broker manage status is online, please offline first!");
            return result.isSuccess();
        }
        BrokerSyncStatusInfo brokerSyncStatusInfo =
                this.brokerRunSyncManageMap.get(curEntity.getBrokerId());
        if (brokerSyncStatusInfo != null) {
            if (brokerSyncStatusInfo.isBrokerRegister()
                    && (curEntity.getManageStatus() == ManageStatus.STATUS_MANAGE_OFFLINE
                    && brokerSyncStatusInfo.getBrokerRunStatus() != TStatusConstants.STATUS_SERVICE_UNDEFINED)) {
                result.setFailResult(DataOpErrCode.DERR_ILLEGAL_STATUS.getCode(),
                        "Broker is processing offline event, please wait and try later!");
                return result.isSuccess();
            }
        }
        if (metaStoreService.delBrokerConf(operator, brokerId, strBuffer, result)) {
            this.brokerRunSyncManageMap.remove(brokerId);
            delBrokerRunData(brokerId);
        }
        return result.isSuccess();
    }

    /**
     * Get broker configure information
     *
     * @param qryEntity
     * @return broker configure information
     */
    public Map<Integer, BrokerConfEntity> confGetBrokerConfInfo(
            BrokerConfEntity qryEntity) {
        return metaStoreService.getBrokerConfInfo(qryEntity);
    }

    /**
     * Get broker configure information
     *
     * @param qryEntity
     * @return broker configure information
     */
    public Map<Integer, BrokerConfEntity> getBrokerConfInfo(Set<Integer> brokerIdSet,
                                                            Set<String> brokerIpSet,
                                                            BrokerConfEntity qryEntity) {
        return metaStoreService.getBrokerConfInfo(brokerIdSet, brokerIpSet, qryEntity);
    }

    /**
     * Manual reload broker config info
     *
     * @param entity
     * @param oldManageStatus
     * @param needFastStart
     * @return true if success otherwise false
     * @throws Exception
     */
    public boolean triggerBrokerConfDataSync(BrokerConfEntity entity,
                                             int oldManageStatus,
                                             boolean needFastStart,
                                             StringBuilder strBuffer,
                                             ProcessResult result) {
        if (!metaStoreService.checkStoreStatus(true, result)) {
            return result.isSuccess();
        }
        BrokerConfEntity curEntity =
                metaStoreService.getBrokerConfByBrokerId(entity.getBrokerId());
        if (curEntity == null) {
            result.setFailResult(DataOpErrCode.DERR_NOT_EXIST.getCode(),
                    "The broker configure not exist!");
            return result.isSuccess();
        }
        String curBrokerConfStr = curEntity.getBrokerDefaultConfInfo();
        List<String> curBrokerTopicConfStrSet =
                getBrokerTopicStrConfigInfo(curEntity, strBuffer);
        BrokerSyncStatusInfo brokerSyncStatusInfo =
                this.brokerRunSyncManageMap.get(entity.getBrokerId());
        if (brokerSyncStatusInfo == null) {
            brokerSyncStatusInfo =
                    new BrokerSyncStatusInfo(entity, curBrokerTopicConfStrSet);
            BrokerSyncStatusInfo tmpBrokerSyncStatusInfo =
                    brokerRunSyncManageMap.putIfAbsent(entity.getBrokerId(), brokerSyncStatusInfo);
            if (tmpBrokerSyncStatusInfo != null) {
                brokerSyncStatusInfo = tmpBrokerSyncStatusInfo;
            }
        }
        if (brokerSyncStatusInfo.isBrokerRegister()
                && brokerSyncStatusInfo.getBrokerRunStatus() != TStatusConstants.STATUS_SERVICE_UNDEFINED) {
            result.setFailResult(DataOpErrCode.DERR_ILLEGAL_STATUS.getCode(),
                    strBuffer.append("The broker is processing online event(")
                    .append(brokerSyncStatusInfo.getBrokerRunStatus())
                    .append("), please try later! ").toString());
            strBuffer.delete(0, strBuffer.length());
            return result.isSuccess();
        }
        if (brokerSyncStatusInfo.isFastStart()) {
            brokerSyncStatusInfo.setFastStart(needFastStart);
        }
        int curManageStatus = curEntity.getManageStatus().getCode();
        if (curManageStatus == TStatusConstants.STATUS_MANAGE_ONLINE
                || curManageStatus == TStatusConstants.STATUS_MANAGE_ONLINE_NOT_WRITE
                || curManageStatus == TStatusConstants.STATUS_MANAGE_ONLINE_NOT_READ) {
            boolean isOnlineUpdate =
                    (oldManageStatus == TStatusConstants.STATUS_MANAGE_ONLINE
                            || oldManageStatus == TStatusConstants.STATUS_MANAGE_ONLINE_NOT_WRITE
                            || oldManageStatus == TStatusConstants.STATUS_MANAGE_ONLINE_NOT_READ);
            brokerSyncStatusInfo.updateCurrBrokerConfInfo(curManageStatus,
                    curEntity.isConfDataUpdated(), curEntity.isBrokerLoaded(), curBrokerConfStr,
                    curBrokerTopicConfStrSet, isOnlineUpdate);
        } else {
            brokerSyncStatusInfo.setBrokerOffline();
        }
        strBuffer.append("triggered broker syncStatus info is ");
        logger.info(brokerSyncStatusInfo.toJsonString(strBuffer, false).toString());
        strBuffer.delete(0, strBuffer.length());
        result.setSuccResult(null);
        return result.isSuccess();
    }

    /**
     * Remove broker and related topic list
     *
     * @param brokerId
     * @param rmvTopics
     * @param strBuffer  the print info string buffer
     * @param result     the process result return
     * @return true if success otherwise false
     */
    public boolean clearRmvedTopicConfInfo(int brokerId,
                                           List<String> rmvTopics,
                                           StringBuilder strBuffer,
                                           ProcessResult result) {
        result.setSuccResult(null);
        if (rmvTopics == null || rmvTopics.isEmpty()) {
            return result.isSuccess();
        }
        if (!metaStoreService.checkStoreStatus(true, result)) {
            return result.isSuccess();
        }
        Map<String, TopicDeployEntity> confEntityMap =
                metaStoreService.getConfiguredTopicInfo(brokerId);
        if (confEntityMap == null || confEntityMap.isEmpty()) {
            return result.isSuccess();
        }
        for (String topicName : rmvTopics) {
            TopicDeployEntity topicEntity = confEntityMap.get(topicName);
            if (topicEntity != null
                    && topicEntity.getTopicStatus() == TopicStatus.STATUS_TOPIC_SOFT_REMOVE) {
                confDelTopicConfInfo(topicEntity.getModifyUser(),
                        topicEntity.getRecordKey(), strBuffer, result);
            }
        }
        result.setSuccResult(null);
        return result.isSuccess();
    }



    /**
     * Find the broker and delete all topic info in the broker
     *
     * @param brokerId
     * @param operator   operator
     * @param strBuffer  the print info string buffer
     * @param result     the process result return
     * @return true if success otherwise false
     */
    public boolean clearAllTopicConfInfo(int brokerId,
                                         String operator,
                                         StringBuilder strBuffer,
                                         ProcessResult result) {
        result.setSuccResult(null);
        if (!metaStoreService.checkStoreStatus(true, result)) {
            return result.isSuccess();
        }
        Map<String, TopicDeployEntity> confEntityMap =
                metaStoreService.getConfiguredTopicInfo(brokerId);
        if (confEntityMap == null || confEntityMap.isEmpty()) {
            return result.isSuccess();
        }
        for (TopicDeployEntity topicEntity : confEntityMap.values()) {
            if (topicEntity == null) {
                continue;
            }
            confDelTopicConfInfo(operator, topicEntity.getRecordKey(), strBuffer, result);
        }
        result.setSuccResult(null);
        return result.isSuccess();
    }

    /**
     * get broker's topicName set,
     * if brokerIds is empty, then return all broker's topicNames
     *
     * @param brokerIdSet
     * @return broker's topicName set
     */
    public Map<Integer, Set<String>> getBrokerTopicConfigInfo(
            Set<Integer> brokerIdSet) {
        return metaStoreService.getConfiguredTopicInfo(brokerIdSet);
    }

    /**
     * get topic's brokerId set,
     * if topicSet is empty, then return all topic's brokerIds
     *
     * @param topicNameSet
     * @return topic's brokerId set
     */
    public Map<String, Map<Integer, String>> getTopicBrokerConfigInfo(Set<String> topicNameSet) {
        return metaStoreService.getTopicBrokerInfo(topicNameSet);
    }

    public Set<String> getTotalConfiguredTopicNames() {
        return metaStoreService.getConfiguredTopicSet();
    }


    public ConcurrentHashMap<Integer, BrokerSyncStatusInfo> getBrokerRunSyncManageMap() {
        return this.brokerRunSyncManageMap;
    }

    public BrokerSyncStatusInfo getBrokerRunSyncStatusInfo(int brokerId) {
        return this.brokerRunSyncManageMap.get(brokerId);
    }

    public BrokerConfEntity getBrokerConfByBrokerId(int brokerId) {
        return metaStoreService.getBrokerConfByBrokerId(brokerId);
    }

    public BrokerConfEntity getBrokerConfByBrokerIp(String brokerIp) {
        return metaStoreService.getBrokerConfByBrokerIp(brokerIp);
    }

    public Map<String, TopicDeployEntity> getBrokerTopicConfEntitySet(int brokerId) {
        return metaStoreService.getConfiguredTopicInfo(brokerId);
    }

    public ConcurrentHashMap<String/* topicName */, TopicInfo> getBrokerRunTopicInfoMap(
            final int brokerId) {
        return this.brokerRunTopicInfoStoreMap.get(brokerId);
    }

    public void removeBrokerRunTopicInfoMap(final int brokerId) {
        this.brokerRunTopicInfoStoreMap.remove(brokerId);
    }

    public void updateBrokerRunTopicInfoMap(final int brokerId,
                                            ConcurrentHashMap<String, TopicInfo> topicInfoMap) {
        this.brokerRunTopicInfoStoreMap.put(brokerId, topicInfoMap);
    }

    public void resetBrokerReportInfo(final int brokerId) {
        BrokerSyncStatusInfo brokerSyncStatusInfo =
                brokerRunSyncManageMap.get(brokerId);
        if (brokerSyncStatusInfo != null) {
            brokerSyncStatusInfo.resetBrokerReportInfo();
        }
        brokerRunTopicInfoStoreMap.remove(brokerId);
    }

    /**
     * Update broker config
     *
     * @param brokerId
     * @param isChanged
     * @param isFasterStart
     * @return true if success otherwise false
     */
    public boolean updateBrokerConfChanged(int brokerId,
                                           boolean isChanged,
                                           boolean isFasterStart,
                                           StringBuilder strBuffer,
                                           ProcessResult result) {
        if (!metaStoreService.checkStoreStatus(true, result)) {
            return result.isSuccess();
        }
        BrokerConfEntity curEntity =
                metaStoreService.getBrokerConfByBrokerId(brokerId);
        if (curEntity == null) {
            return false;
        }
        // This function needs to be optimized continue
        if (isChanged) {
            if (!curEntity.isConfDataUpdated()) {
                curEntity.setConfDataUpdated();
                modBrokerConfig(curEntity, strBuffer, result);
            }
            if (curEntity.getManageStatus().isApplied()) {
                BrokerSyncStatusInfo brokerSyncStatusInfo =
                        brokerRunSyncManageMap.get(curEntity.getBrokerId());
                if (brokerSyncStatusInfo == null) {
                    List<String> newBrokerTopicConfStrSet =
                            getBrokerTopicStrConfigInfo(curEntity, strBuffer);
                    brokerSyncStatusInfo =
                            new BrokerSyncStatusInfo(curEntity, newBrokerTopicConfStrSet);
                    BrokerSyncStatusInfo tmpBrokerSyncStatusInfo =
                            brokerRunSyncManageMap.putIfAbsent(curEntity.getBrokerId(), brokerSyncStatusInfo);
                    if (tmpBrokerSyncStatusInfo != null) {
                        brokerSyncStatusInfo = tmpBrokerSyncStatusInfo;
                    }
                }
                if (brokerSyncStatusInfo.isFastStart()) {
                    brokerSyncStatusInfo.setFastStart(isFasterStart);
                }
                if (!brokerSyncStatusInfo.isBrokerConfChanged()) {
                    brokerSyncStatusInfo.setBrokerConfChanged();
                }
            }
        } else {
            if (curEntity.isConfDataUpdated()) {
                curEntity.setBrokerLoaded();
                modBrokerConfig(curEntity, strBuffer, result);
            }
            if (curEntity.getManageStatus().isApplied()) {
                BrokerSyncStatusInfo brokerSyncStatusInfo =
                        brokerRunSyncManageMap.get(curEntity.getBrokerId());
                if (brokerSyncStatusInfo == null) {
                    List<String> newBrokerTopicConfStrSet =
                            getBrokerTopicStrConfigInfo(curEntity, strBuffer);
                    brokerSyncStatusInfo =
                            new BrokerSyncStatusInfo(curEntity, newBrokerTopicConfStrSet);
                    BrokerSyncStatusInfo tmpBrokerSyncStatusInfo =
                            brokerRunSyncManageMap.putIfAbsent(curEntity.getBrokerId(),
                                    brokerSyncStatusInfo);
                    if (tmpBrokerSyncStatusInfo != null) {
                        brokerSyncStatusInfo = tmpBrokerSyncStatusInfo;
                    }
                }
                if (brokerSyncStatusInfo.isBrokerConfChanged()) {
                    brokerSyncStatusInfo.setBrokerLoaded();
                    brokerSyncStatusInfo.setFastStart(isFasterStart);
                }
            }
        }
        return true;
    }

    public void updateBrokerMaps(BrokerConfEntity entity) {
        if (entity != null) {
            String brokerReg =
                    this.brokersMap.putIfAbsent(entity.getBrokerId(),
                            entity.getSimpleBrokerInfo());
            String brokerTLSReg =
                    this.brokersTLSMap.putIfAbsent(entity.getBrokerId(),
                            entity.getSimpleTLSBrokerInfo());
            if (brokerReg == null
                    || brokerTLSReg == null
                    || !brokerReg.equals(entity.getSimpleBrokerInfo())
                    || !brokerTLSReg.equals(entity.getSimpleTLSBrokerInfo())) {
                if (brokerReg != null
                        && !brokerReg.equals(entity.getSimpleBrokerInfo())) {
                    this.brokersMap.put(entity.getBrokerId(), entity.getSimpleBrokerInfo());
                }
                if (brokerTLSReg != null
                        && !brokerTLSReg.equals(entity.getSimpleTLSBrokerInfo())) {
                    this.brokersTLSMap.put(entity.getBrokerId(), entity.getSimpleTLSBrokerInfo());
                }
                this.lastBrokerUpdatedTime = System.currentTimeMillis();
                this.brokerInfoCheckSum.set(this.lastBrokerUpdatedTime);
            }
        }
    }

    public void delBrokerRunData(int brokerId) {
        if (brokerId == TBaseConstants.META_VALUE_UNDEFINED) {
            return;
        }
        String brokerReg = this.brokersMap.remove(brokerId);
        String brokerTLSReg = this.brokersTLSMap.remove(brokerId);
        if (brokerReg != null || brokerTLSReg != null) {
            this.lastBrokerUpdatedTime = System.currentTimeMillis();
            this.brokerInfoCheckSum.set(this.lastBrokerUpdatedTime);
        }
    }

    // ////////////////////////////////////////////////////////////////////////////

    public List<TopicProcessResult> addTopicDeployInfo(BaseEntity opEntity,
                                                       Set<Integer> brokerIdSet,
                                                       Set<String> topicNameSet,
                                                       TopicPropGroup topicPropInfo,
                                                       StringBuilder sBuilder,
                                                       ProcessResult result) {
        TopicDeployEntity deployConf;
        List<TopicProcessResult> retInfo = new ArrayList<>();
        // add topic control info
        addIfAbsentTopicCtrlConf(topicNameSet,
                opEntity.getCreateUser(), sBuilder, result);
        result.clear();
        // add topic deployment record
        for (Integer brokerId : brokerIdSet) {
            BrokerConfEntity brokerConf = getBrokerConfByBrokerId(brokerId);
            if (brokerConf == null) {
                result.setFailResult(DataOpErrCode.DERR_NOT_EXIST.getCode(),
                        DataOpErrCode.DERR_NOT_EXIST.getDescription());
                retInfo.add(new TopicProcessResult(brokerId, "", result));
                continue;
            }

            for (String topicName : topicNameSet) {
                TopicDeployEntity deployInfo = getTopicConfInfo(brokerId, topicName);
                if (deployInfo != null) {
                    result.setFailResult(DataOpErrCode.DERR_EXISTED.getCode(),
                            DataOpErrCode.DERR_EXISTED.getDescription());
                    retInfo.add(new TopicProcessResult(brokerId, topicName, result));
                    continue;
                }
                deployConf = new TopicDeployEntity(opEntity, brokerConf.getBrokerId(),
                        brokerConf.getBrokerIp(), brokerConf.getBrokerPort(), topicName);
                deployConf.setTopicProps(brokerConf.getTopicProps());
                deployConf.updModifyInfo(opEntity.getDataVerId(),
                        TBaseConstants.META_VALUE_UNDEFINED,
                        TBaseConstants.META_VALUE_UNDEFINED, null,
                        TopicStatus.STATUS_TOPIC_OK, topicPropInfo);
                metaStoreService.addTopicConf(deployConf, sBuilder, result);
                retInfo.add(new TopicProcessResult(brokerId, topicName, result));
            }
        }
        return retInfo;
    }

    public TopicProcessResult addTopicDeployInfo(TopicDeployEntity deployEntity,
                                                 StringBuilder sBuilder,
                                                 ProcessResult result) {
        // check broker configure exist
        BrokerConfEntity brokerConf =
                getBrokerConfByBrokerId(deployEntity.getBrokerId());
        if (brokerConf == null) {
            result.setFailResult(DataOpErrCode.DERR_NOT_EXIST.getCode(),
                    DataOpErrCode.DERR_NOT_EXIST.getDescription());
            return new TopicProcessResult(deployEntity.getBrokerId(), "", result);
        }
        // add topic control info
        addIfAbsentTopicCtrlConf(deployEntity, sBuilder, result);
        // add topic deployment record
        TopicDeployEntity curDeployInfo =
                metaStoreService.getTopicConfByeRecKey(deployEntity.getRecordKey());
        if (curDeployInfo != null) {
            result.setFailResult(DataOpErrCode.DERR_EXISTED.getCode(),
                    DataOpErrCode.DERR_EXISTED.getDescription());
            return new TopicProcessResult(deployEntity.getBrokerId(),
                    deployEntity.getTopicName(), result);
        }
        metaStoreService.addTopicConf(deployEntity, sBuilder, result);
        return new TopicProcessResult(deployEntity.getBrokerId(),
                deployEntity.getTopicName(), result);
    }

    /**
     * Modify topic config
     *
     * @param result     the process result return
     * @return true if success otherwise false
     */
    public List<TopicProcessResult> modTopicConfig(BaseEntity opEntity,
                                                   Set<Integer> brokerIdSet,
                                                   Set<String> topicNameSet,
                                                   TopicPropGroup topicProps,
                                                   StringBuilder sBuilder,
                                                   ProcessResult result) {
        List<TopicProcessResult> retInfo = new ArrayList<>();
        // add topic control info
        addIfAbsentTopicCtrlConf(topicNameSet,
                opEntity.getModifyUser(), sBuilder, result);
        result.clear();
        // add topic deployment record
        TopicDeployEntity curEntity;
        TopicDeployEntity newEntity;
        for (Integer brokerId : brokerIdSet) {
            BrokerConfEntity brokerConf = getBrokerConfByBrokerId(brokerId);
            if (brokerConf == null) {
                result.setFailResult(DataOpErrCode.DERR_NOT_EXIST.getCode(),
                        DataOpErrCode.DERR_NOT_EXIST.getDescription());
                retInfo.add(new TopicProcessResult(brokerId, "", result));
                continue;
            }
            for (String topicName : topicNameSet) {
                curEntity = getTopicConfInfo(brokerId, topicName);
                if (curEntity == null) {
                    result.setFailResult(DataOpErrCode.DERR_NOT_EXIST.getCode(),
                            DataOpErrCode.DERR_NOT_EXIST.getDescription());
                    retInfo.add(new TopicProcessResult(brokerId, topicName, result));
                    continue;
                }
                if (!curEntity.isValidTopicStatus()) {
                    result.setFailResult(DataOpErrCode.DERR_ILLEGAL_STATUS.getCode(),
                            sBuilder.append("Topic of ").append(topicName)
                                    .append("is deleted softly in brokerId=").append(brokerId)
                                    .append(", please resume the record or hard removed first!")
                                    .toString());
                    sBuilder.delete(0, sBuilder.length());
                    retInfo.add(new TopicProcessResult(brokerId, topicName, result));
                    continue;
                }
                if (topicProps != null) {
                    if (topicProps.getNumPartitions() != TBaseConstants.META_VALUE_UNDEFINED
                            && topicProps.getNumPartitions() < curEntity.getNumPartitions()) {
                        result.setFailResult(DataOpErrCode.DERR_ILLEGAL_VALUE.getCode(),
                                sBuilder.append("Partition value is less than before,")
                                        .append("please confirm the configure first! brokerId=")
                                        .append(curEntity.getBrokerId()).append(", topicName=")
                                        .append(curEntity.getTopicName())
                                        .append(", old Partition value is ")
                                        .append(curEntity.getNumPartitions())
                                        .append(", new Partition value is ")
                                        .append(topicProps.getNumPartitions()).toString());
                        sBuilder.delete(0, sBuilder.length());
                        retInfo.add(new TopicProcessResult(brokerId, topicName, result));
                        continue;
                    }
                    if (topicProps.getNumTopicStores() != TBaseConstants.META_VALUE_UNDEFINED
                            && topicProps.getNumTopicStores() < curEntity.getNumTopicStores()) {
                        result.setFailResult(DataOpErrCode.DERR_ILLEGAL_VALUE.getCode(),
                                sBuilder.append("TopicStores value is less than before,")
                                        .append("please confirm the configure first! brokerId=")
                                        .append(curEntity.getBrokerId()).append(", topicName=")
                                        .append(curEntity.getTopicName())
                                        .append(", old TopicStores value is ")
                                        .append(curEntity.getNumTopicStores())
                                        .append(", new TopicStores value is ")
                                        .append(topicProps.getNumTopicStores()).toString());
                        sBuilder.delete(0, sBuilder.length());
                        retInfo.add(new TopicProcessResult(brokerId, topicName, result));
                        continue;
                    }
                }
                newEntity = curEntity.clone();
                newEntity.updBaseModifyInfo(opEntity);
                if (!newEntity.updModifyInfo(opEntity.getDataVerId(),
                        TBaseConstants.META_VALUE_UNDEFINED,
                        TBaseConstants.META_VALUE_UNDEFINED,
                        null, null, topicProps)) {
                    result.setFailResult(DataOpErrCode.DERR_UNCHANGED.getCode(),
                            sBuilder.append("Data not changed for brokerId=")
                                    .append(curEntity.getBrokerId()).append(", topicName=")
                                    .append(curEntity.getTopicName()).toString());
                    sBuilder.delete(0, sBuilder.length());
                    retInfo.add(new TopicProcessResult(brokerId, topicName, result));
                    continue;
                }
                metaStoreService.updTopicConf(newEntity, sBuilder, result);
                retInfo.add(new TopicProcessResult(brokerId, topicName, result));
            }
        }
        return retInfo;
    }

    /**
     * Modify topic config
     *
     * @param result     the process result return
     * @return true if success otherwise false
     */
    public List<TopicProcessResult> modDelOrRmvTopicConf(BaseEntity opEntity,
                                                         Set<Integer> brokerIdSet,
                                                         Set<String> topicNameSet,
                                                         TopicStatus topicStatus,
                                                         StringBuilder sBuilder,
                                                         ProcessResult result) {
        TopicDeployEntity curEntity;
        TopicDeployEntity newEntity;
        List<TopicProcessResult> retInfo = new ArrayList<>();
        // add topic deployment record
        for (Integer brokerId : brokerIdSet) {
            BrokerConfEntity brokerConf = getBrokerConfByBrokerId(brokerId);
            if (brokerConf == null) {
                result.setFailResult(DataOpErrCode.DERR_NOT_EXIST.getCode(),
                        DataOpErrCode.DERR_NOT_EXIST.getDescription());
                retInfo.add(new TopicProcessResult(brokerId, "", result));
                continue;
            }
            for (String topicName : topicNameSet) {
                curEntity = getTopicConfInfo(brokerId, topicName);
                if (curEntity == null) {
                    result.setFailResult(DataOpErrCode.DERR_NOT_EXIST.getCode(),
                            DataOpErrCode.DERR_NOT_EXIST.getDescription());
                    retInfo.add(new TopicProcessResult(brokerId, topicName, result));
                    continue;
                }
                if (curEntity.isAcceptPublish()
                        || curEntity.isAcceptSubscribe()) {  // still accept publish and subscribe
                    result.setFailResult(DataOpErrCode.DERR_ILLEGAL_STATUS.getCode(),
                            sBuilder.append("The topic ").append(topicName)
                                    .append("'s acceptPublish and acceptSubscribe status must be false in broker=")
                                    .append(brokerId).append(" before topic deleted!").toString());
                    retInfo.add(new TopicProcessResult(brokerId, topicName, result));
                    continue;
                }
                if ((topicStatus == TopicStatus.STATUS_TOPIC_SOFT_DELETE
                        && !curEntity.isValidTopicStatus())
                        || (topicStatus == TopicStatus.STATUS_TOPIC_SOFT_REMOVE
                        && curEntity.getTopicStatus() != TopicStatus.STATUS_TOPIC_SOFT_DELETE)) {
                    result.setSuccResult("");
                    retInfo.add(new TopicProcessResult(brokerId, topicName, result));
                    continue;
                }
                newEntity = curEntity.clone();
                newEntity.updBaseModifyInfo(opEntity);
                if (!newEntity.updModifyInfo(opEntity.getDataVerId(),
                        TBaseConstants.META_VALUE_UNDEFINED,
                        TBaseConstants.META_VALUE_UNDEFINED,
                        null, topicStatus, null)) {
                    result.setFailResult(DataOpErrCode.DERR_UNCHANGED.getCode(),
                            sBuilder.append("Data not changed for brokerId=")
                                    .append(curEntity.getBrokerId()).append(", topicName=")
                                    .append(curEntity.getTopicName()).toString());
                    sBuilder.delete(0, sBuilder.length());
                    retInfo.add(new TopicProcessResult(brokerId, topicName, result));
                    continue;
                }
                metaStoreService.updTopicConf(newEntity, sBuilder, result);
                retInfo.add(new TopicProcessResult(brokerId, topicName, result));
            }
        }
        return retInfo;
    }

    /**
     * Modify topic config
     *
     * @param result     the process result return
     * @return true if success otherwise false
     */
    public List<TopicProcessResult> modRedoDelTopicConf(BaseEntity opEntity,
                                                        Set<Integer> brokerIdSet,
                                                        Set<String> topicNameSet,
                                                        StringBuilder sBuilder,
                                                        ProcessResult result) {
        TopicDeployEntity curEntity;
        TopicDeployEntity newEntity;
        List<TopicProcessResult> retInfo = new ArrayList<>();
        // add topic deployment record
        for (Integer brokerId : brokerIdSet) {
            BrokerConfEntity brokerConf = getBrokerConfByBrokerId(brokerId);
            if (brokerConf == null) {
                result.setFailResult(DataOpErrCode.DERR_NOT_EXIST.getCode(),
                        DataOpErrCode.DERR_NOT_EXIST.getDescription());
                retInfo.add(new TopicProcessResult(brokerId, "", result));
                continue;
            }
            for (String topicName : topicNameSet) {
                curEntity = getTopicConfInfo(brokerId, topicName);
                if (curEntity == null) {
                    result.setFailResult(DataOpErrCode.DERR_NOT_EXIST.getCode(),
                            DataOpErrCode.DERR_NOT_EXIST.getDescription());
                    retInfo.add(new TopicProcessResult(brokerId, topicName, result));
                    continue;
                }
                if (curEntity.isAcceptPublish()
                        || curEntity.isAcceptSubscribe()) {  // still accept publish and subscribe
                    result.setFailResult(DataOpErrCode.DERR_ILLEGAL_STATUS.getCode(),
                            sBuilder.append("The topic ").append(topicName)
                                    .append("'s acceptPublish and acceptSubscribe status must be false in broker=")
                                    .append(brokerId).append(" before topic deleted!").toString());
                    retInfo.add(new TopicProcessResult(brokerId, topicName, result));
                    continue;
                }
                if (curEntity.getTopicStatus() != TopicStatus.STATUS_TOPIC_SOFT_DELETE) {
                    if (curEntity.isValidTopicStatus()) {
                        result.setSuccResult(null);
                    } else {
                        result.setFailResult(DataOpErrCode.DERR_ILLEGAL_STATUS.getCode(),
                                sBuilder.append("Topic of ").append(topicName)
                                        .append("is in removing flow in brokerId=")
                                        .append(curEntity.getBrokerId())
                                        .append(", please wait until remove process finished!")
                                        .toString());
                        sBuilder.delete(0, sBuilder.length());
                    }
                    retInfo.add(new TopicProcessResult(brokerId, topicName, result));
                    continue;
                }
                newEntity = curEntity.clone();
                newEntity.updBaseModifyInfo(opEntity);
                if (!newEntity.updModifyInfo(opEntity.getDataVerId(),
                        TBaseConstants.META_VALUE_UNDEFINED,
                        TBaseConstants.META_VALUE_UNDEFINED, null,
                        TopicStatus.STATUS_TOPIC_OK, null)) {
                    result.setFailResult(DataOpErrCode.DERR_UNCHANGED.getCode(),
                            sBuilder.append("Data not changed for brokerId=")
                                    .append(curEntity.getBrokerId()).append(", topicName=")
                                    .append(curEntity.getTopicName()).toString());
                    sBuilder.delete(0, sBuilder.length());
                    retInfo.add(new TopicProcessResult(brokerId, topicName, result));
                    continue;
                }
                metaStoreService.updTopicConf(newEntity, sBuilder, result);
                retInfo.add(new TopicProcessResult(brokerId, topicName, result));
            }
        }
        return retInfo;
    }


    /**
     * Get broker topic entity, if query entity is null, return all topic entity
     *
     * @param qryEntity query conditions
     * @return topic entity map
     */
    public Map<String, List<TopicDeployEntity>> getTopicConfEntityMap(Set<String> topicNameSet,
                                                                      Set<Integer> brokerIdSet,
                                                                      TopicDeployEntity qryEntity) {
        return metaStoreService.getTopicConfMap(topicNameSet, brokerIdSet, qryEntity);
    }

    public TopicDeployEntity getTopicConfInfo(int brokerId, String topicName) {
        return metaStoreService.getTopicConf(brokerId, topicName);
    }

    /**
     * Get broker topic entity, if query entity is null, return all topic entity
     *
     * @return topic entity map
     */
    public Map<Integer, List<TopicDeployEntity>> getTopicDeployInfoMap(Set<String> topicNameSet,
                                                                       Set<Integer> brokerIdSet) {
        return metaStoreService.getTopicDeployInfoMap(topicNameSet, brokerIdSet);
    }

    public Map<String, List<TopicDeployEntity>> getTopicConfMapByTopicAndBrokerIds(
            Set<String> topicNameSet, Set<Integer> brokerIdSet) {
        return metaStoreService.getTopicDepInfoByTopicBrokerId(topicNameSet, brokerIdSet);
    }




    private boolean confDelTopicConfInfo(String operator,
                                         String recordKey,
                                         StringBuilder strBuffer,
                                         ProcessResult result) {
        return metaStoreService.delTopicConf(operator,
                recordKey, strBuffer, result);
    }

    public List<String> getBrokerTopicStrConfigInfo(
            BrokerConfEntity brokerConfEntity, StringBuilder sBuffer) {
        return inGetTopicConfStrInfo(brokerConfEntity, false, sBuffer);
    }

    public List<String> getBrokerRemovedTopicStrConfigInfo(
            BrokerConfEntity brokerConfEntity, StringBuilder sBuffer) {
        return inGetTopicConfStrInfo(brokerConfEntity, true, sBuffer);
    }

    private List<String> inGetTopicConfStrInfo(BrokerConfEntity brokerEntity,
                                               boolean isRemoved, StringBuilder sBuffer) {
        List<String> topicConfStrs = new ArrayList<>();
        Map<String, TopicDeployEntity> topicEntityMap =
                metaStoreService.getConfiguredTopicInfo(brokerEntity.getBrokerId());
        if (topicEntityMap.isEmpty()) {
            return topicConfStrs;
        }
        TopicPropGroup defTopicProps = brokerEntity.getTopicProps();
        ClusterSettingEntity clusterDefConf =
                metaStoreService.getClusterConfig();
        int defMsgSizeInB = clusterDefConf.getMaxMsgSizeInB();
        for (TopicDeployEntity topicEntity : topicEntityMap.values()) {
            /*
             * topic:partNum:acceptPublish:acceptSubscribe:unflushThreshold:unflushInterval:deleteWhen:
             * deletePolicy:filterStatusId:statusId
             */
            if ((isRemoved && !topicEntity.isInRemoving())
                    || (!isRemoved && topicEntity.isInRemoving())) {
                continue;
            }
            sBuffer.append(topicEntity.getTopicName());
            TopicPropGroup topicProps = topicEntity.getTopicProps();
            if (topicProps.getNumPartitions() == defTopicProps.getNumPartitions()) {
                sBuffer.append(TokenConstants.ATTR_SEP).append(" ");
            } else {
                sBuffer.append(TokenConstants.ATTR_SEP).append(topicProps.getNumPartitions());
            }
            if (topicProps.isAcceptPublish() == defTopicProps.isAcceptPublish()) {
                sBuffer.append(TokenConstants.ATTR_SEP).append(" ");
            } else {
                sBuffer.append(TokenConstants.ATTR_SEP).append(topicProps.isAcceptPublish());
            }
            if (topicProps.isAcceptSubscribe() == defTopicProps.isAcceptSubscribe()) {
                sBuffer.append(TokenConstants.ATTR_SEP).append(" ");
            } else {
                sBuffer.append(TokenConstants.ATTR_SEP).append(topicProps.isAcceptSubscribe());
            }
            if (topicProps.getUnflushThreshold() == defTopicProps.getUnflushThreshold()) {
                sBuffer.append(TokenConstants.ATTR_SEP).append(" ");
            } else {
                sBuffer.append(TokenConstants.ATTR_SEP).append(topicProps.getUnflushThreshold());
            }
            if (topicProps.getUnflushInterval() == defTopicProps.getUnflushInterval()) {
                sBuffer.append(TokenConstants.ATTR_SEP).append(" ");
            } else {
                sBuffer.append(TokenConstants.ATTR_SEP).append(topicProps.getUnflushInterval());
            }
            sBuffer.append(TokenConstants.ATTR_SEP).append(" ");
            if (topicProps.getDeletePolicy().equals(defTopicProps.getDeletePolicy())) {
                sBuffer.append(TokenConstants.ATTR_SEP).append(" ");
            } else {
                sBuffer.append(TokenConstants.ATTR_SEP).append(topicProps.getDeletePolicy());
            }
            if (topicProps.getNumTopicStores() == defTopicProps.getNumTopicStores()) {
                sBuffer.append(TokenConstants.ATTR_SEP).append(" ");
            } else {
                sBuffer.append(TokenConstants.ATTR_SEP).append(topicProps.getNumTopicStores());
            }
            sBuffer.append(TokenConstants.ATTR_SEP).append(topicEntity.getTopicStatusId());
            if (topicProps.getUnflushDataHold() == defTopicProps.getUnflushDataHold()) {
                sBuffer.append(TokenConstants.ATTR_SEP).append(" ");
            } else {
                sBuffer.append(TokenConstants.ATTR_SEP).append(topicProps.getUnflushDataHold());
            }
            if (topicProps.getMemCacheMsgSizeInMB() == defTopicProps.getMemCacheMsgSizeInMB()) {
                sBuffer.append(TokenConstants.ATTR_SEP).append(" ");
            } else {
                sBuffer.append(TokenConstants.ATTR_SEP).append(topicProps.getMemCacheMsgSizeInMB());
            }
            if (topicProps.getMemCacheMsgCntInK() == defTopicProps.getMemCacheMsgCntInK()) {
                sBuffer.append(TokenConstants.ATTR_SEP).append(" ");
            } else {
                sBuffer.append(TokenConstants.ATTR_SEP).append(topicProps.getMemCacheMsgCntInK());
            }
            if (topicProps.getMemCacheFlushIntvl() == defTopicProps.getMemCacheFlushIntvl()) {
                sBuffer.append(TokenConstants.ATTR_SEP).append(" ");
            } else {
                sBuffer.append(TokenConstants.ATTR_SEP).append(topicProps.getMemCacheFlushIntvl());
            }
            int maxMsgSize = defMsgSizeInB;
            TopicCtrlEntity topicCtrlEntity =
                    metaStoreService.getTopicCtrlConf(topicEntity.getTopicName());
            if (topicCtrlEntity != null) {
                if (topicCtrlEntity.getMaxMsgSizeInB() != TBaseConstants.META_VALUE_UNDEFINED) {
                    maxMsgSize = topicCtrlEntity.getMaxMsgSizeInB();
                }
            }
            if (maxMsgSize == defMsgSizeInB) {
                sBuffer.append(TokenConstants.ATTR_SEP).append(" ");
            } else {
                sBuffer.append(TokenConstants.ATTR_SEP).append(maxMsgSize);
            }
            topicConfStrs.add(sBuffer.toString());
            sBuffer.delete(0, sBuffer.length());
        }
        return topicConfStrs;
    }

    // /////////////////////////////////////////////////////////////////////////////////

    /**
     * Add or Update topic control configure info
     *
     * @param sBuffer  the print info string buffer
     * @param result     the process result return
     * @return true if success otherwise false
     */
    public List<TopicProcessResult> addOrUpdTopicCtrlConf(BaseEntity opInfoEntity,
                                                          Set<String> topicNameSet,
                                                          int topicNameId,
                                                          Boolean enableTopicAuth,
                                                          int maxMsgSizeInMB,
                                                          StringBuilder sBuffer,
                                                          ProcessResult result) {
        TopicCtrlEntity curEntity;
        TopicCtrlEntity newEntity;
        List<TopicProcessResult> retInfoList = new ArrayList<>();
        for (String topicName : topicNameSet) {
            curEntity = metaStoreService.getTopicCtrlConf(topicName);
            if (curEntity == null) {
                newEntity = new TopicCtrlEntity(opInfoEntity, topicName);
                newEntity.updModifyInfo(opInfoEntity.getDataVerId(),
                        topicNameId, maxMsgSizeInMB, enableTopicAuth);
                metaStoreService.addTopicCtrlConf(newEntity, sBuffer, result);
            } else {
                newEntity = curEntity.clone();
                newEntity.updBaseModifyInfo(opInfoEntity);
                if (!newEntity.updModifyInfo(opInfoEntity.getDataVerId(),
                        topicNameId, maxMsgSizeInMB, enableTopicAuth)) {
                    result.setSuccResult(null);
                    retInfoList.add(new TopicProcessResult(0, topicName, result));
                    continue;
                }
                metaStoreService.updTopicCtrlConf(newEntity, sBuffer, result);
            }
            retInfoList.add(new TopicProcessResult(0, topicName, result));
        }
        return retInfoList;
    }

    /**
     * Add or Update topic control configure info
     *
     * @param inEntity  the topic control info entity will be add
     * @param sBuffer   the print info string buffer
     * @param result    the process result return
     * @return true if success otherwise false
     */
    public TopicProcessResult addOrUpdTopicCtrlConf(TopicCtrlEntity inEntity,
                                                    StringBuilder sBuffer,
                                                    ProcessResult result) {

        TopicCtrlEntity curEntity =
                metaStoreService.getTopicCtrlConf(inEntity.getTopicName());
        if (curEntity == null) {
            metaStoreService.addTopicCtrlConf(inEntity, sBuffer, result);
        } else {
            TopicCtrlEntity newEntity2 = curEntity.clone();
            newEntity2.updBaseModifyInfo(inEntity);
            if (!newEntity2.updModifyInfo(inEntity.getDataVerId(), inEntity.getTopicId(),
                    inEntity.getMaxMsgSizeInMB(), inEntity.isAuthCtrlEnable())) {
                result.setSuccResult(null);
                return new TopicProcessResult(0, inEntity.getTopicName(), result);
            }
            metaStoreService.updTopicCtrlConf(newEntity2, sBuffer, result);
        }
        return new TopicProcessResult(0, inEntity.getTopicName(), result);
    }

    /**
     * Delete topic control configure
     *
     * @param operator   operator
     * @param topicName  the topicName will be deleted
     * @param sBuffer  the print info string buffer
     * @param result     the process result return
     * @return true if success otherwise false
     */
    public boolean delTopicCtrlConf(String operator,
                                    String topicName,
                                    StringBuilder sBuffer,
                                    ProcessResult result) {
        // check current status
        if (!metaStoreService.checkStoreStatus(true, result)) {
            return result.isSuccess();
        }
        if (metaStoreService.isTopicDeployed(topicName)) {
            result.setFailResult(DataOpErrCode.DERR_ILLEGAL_STATUS.getCode(),
                    sBuffer.append("TopicName ").append(topicName)
                            .append(" is in using, please delete the deploy info first!")
                            .toString());
            return result.isSuccess();
        }
        if (metaStoreService.isTopicNameInUsed(topicName)) {
            result.setFailResult(DataOpErrCode.DERR_ILLEGAL_STATUS.getCode(),
                    sBuffer.append("TopicName ").append(topicName)
                            .append(" is in using, please delete the consume control info first!")
                            .toString());
            return result.isSuccess();
        }
        metaStoreService.delTopicCtrlConf(operator, topicName, sBuffer, result);
        return result.isSuccess();
    }


    /**
     * Add if absent topic control configure info
     *
     * @param deployEntity  the topic deploy info will be add
     * @param strBuffer     the print info string buffer
     * @param result        the process result return
     * @return true if success otherwise false
     */
    public void addIfAbsentTopicCtrlConf(TopicDeployEntity deployEntity,
                                         StringBuilder strBuffer,
                                         ProcessResult result) {
        TopicCtrlEntity curEntity =
                metaStoreService.getTopicCtrlConf(deployEntity.getTopicName());
        if (curEntity != null) {
            return;
        }
        int maxMsgSizeInMB = TBaseConstants.META_MIN_ALLOWED_MESSAGE_SIZE_MB;
        ClusterSettingEntity defSetting = metaStoreService.getClusterConfig();
        if (defSetting != null) {
            maxMsgSizeInMB = defSetting.getMaxMsgSizeInMB();
        }
        curEntity = new TopicCtrlEntity(deployEntity.getTopicName(),
                deployEntity.getTopicId(), maxMsgSizeInMB, deployEntity.getCreateUser());
        metaStoreService.addTopicCtrlConf(curEntity, strBuffer, result);
        return;
    }

    /**
     * Add if absent topic control configure info
     *
     * @param topicNameSet  the topic name will be add
     * @param operator the topic name id will be add
     * @param operator   operator
     * @param strBuffer  the print info string buffer
     */
    public void addIfAbsentTopicCtrlConf(Set<String> topicNameSet, String operator,
                                         StringBuilder strBuffer, ProcessResult result) {
        TopicCtrlEntity curEntity;
        int maxMsgSizeInMB = TBaseConstants.META_MIN_ALLOWED_MESSAGE_SIZE_MB;
        ClusterSettingEntity defSetting = metaStoreService.getClusterConfig();
        if (defSetting != null) {
            maxMsgSizeInMB = defSetting.getMaxMsgSizeInMB();
        }
        for (String topicName : topicNameSet) {
            curEntity = metaStoreService.getTopicCtrlConf(topicName);
            if (curEntity != null) {
                continue;
            }
            curEntity = new TopicCtrlEntity(topicName,
                    TBaseConstants.META_VALUE_UNDEFINED, maxMsgSizeInMB, operator);
            metaStoreService.addTopicCtrlConf(curEntity, strBuffer, result);
        }
    }


    public TopicCtrlEntity getTopicCtrlByTopicName(String topicName) {
        return this.metaStoreService.getTopicCtrlConf(topicName);
    }

    public int getTopicMaxMsgSizeInMB(String topicName) {
        // get maxMsgSizeInMB info
        ClusterSettingEntity clusterSettingEntity = getClusterDefSetting(false);
        int maxMsgSizeInMB = clusterSettingEntity.getMaxMsgSizeInMB();
        TopicCtrlEntity topicCtrlEntity = getTopicCtrlByTopicName(topicName);
        if (topicCtrlEntity != null) {
            maxMsgSizeInMB = topicCtrlEntity.getMaxMsgSizeInMB();
        }
        return maxMsgSizeInMB;
    }

    /**
     * Get topic control entity list
     *
     * @param qryEntity
     * @return entity list
     */
    public List<TopicCtrlEntity> queryTopicCtrlConf(TopicCtrlEntity qryEntity) {
        return metaStoreService.getTopicCtrlConf(qryEntity);
    }

    public Map<String, TopicCtrlEntity> getTopicCtrlConf(Set<String> topicNameSet,
                                                         TopicCtrlEntity qryEntity) {
        return metaStoreService.getTopicCtrlConf(topicNameSet, qryEntity);
    }



    // //////////////////////////////////////////////////////////////////////////////

    public boolean addClusterDefSetting(BaseEntity opEntity, int brokerPort,
                                        int brokerTlsPort, int brokerWebPort,
                                        int maxMsgSizeMB, int qryPriorityId,
                                        Boolean flowCtrlEnable, int flowRuleCnt,
                                        String flowCtrlInfo, TopicPropGroup topicProps,
                                        StringBuilder strBuffer, ProcessResult result) {
        ClusterSettingEntity newConf =
                new ClusterSettingEntity(opEntity);
        newConf.fillDefaultValue();
        newConf.updModifyInfo(opEntity.getDataVerId(), brokerPort,
                brokerTlsPort, brokerWebPort, maxMsgSizeMB, qryPriorityId,
                flowCtrlEnable, flowRuleCnt, flowCtrlInfo, topicProps);
        return metaStoreService.addClusterConfig(newConf, strBuffer, result);
    }

    /**
     * Update cluster default setting
     *
     * @return true if success otherwise false
     */
    public boolean modClusterDefSetting(BaseEntity opEntity, int brokerPort,
                                        int brokerTlsPort, int brokerWebPort,
                                        int maxMsgSizeMB, int qryPriorityId,
                                        Boolean flowCtrlEnable, int flowRuleCnt,
                                        String flowCtrlInfo, TopicPropGroup topicProps,
                                        StringBuilder strBuffer, ProcessResult result) {
        ClusterSettingEntity curConf =
                metaStoreService.getClusterConfig();
        if (curConf == null) {
            result.setFailResult(DataOpErrCode.DERR_EXISTED.getCode(),
                    DataOpErrCode.DERR_EXISTED.getDescription());
            return result.isSuccess();
        }
        ClusterSettingEntity newConf = curConf.clone();
        newConf.updBaseModifyInfo(opEntity);
        if (newConf.updModifyInfo(opEntity.getDataVerId(), brokerPort,
                brokerTlsPort, brokerWebPort, maxMsgSizeMB, qryPriorityId,
                flowCtrlEnable, flowRuleCnt, flowCtrlInfo, topicProps)) {
            metaStoreService.updClusterConfig(newConf, strBuffer, result);
        } else {
            result.setFailResult(DataOpErrCode.DERR_UNCHANGED.getCode(),
                    DataOpErrCode.DERR_UNCHANGED.getDescription());
        }
        return result.isSuccess();
    }


    public ClusterSettingEntity getClusterDefSetting(boolean isMustConf) {
        ClusterSettingEntity curClsSetting =
                metaStoreService.getClusterConfig();
        if (!isMustConf && curClsSetting == null) {
            curClsSetting = defClusterSetting;
        }
        return curClsSetting;
    }

    // //////////////////////////////////////////////////////////////////////////////

    public GroupProcessResult addGroupResCtrlConf(BaseEntity opEntity, String groupName,
                                                  Boolean consumeEnable, String disableRsn,
                                                  Boolean resCheckEnable, int allowedBClientRate,
                                                  int qryPriorityId, Boolean flowCtrlEnable,
                                                  int flowRuleCnt, String flowCtrlInfo,
                                                  StringBuilder sBuilder, ProcessResult result) {
        GroupResCtrlEntity entity =
                new GroupResCtrlEntity(opEntity, groupName);
        entity.updModifyInfo(opEntity.getDataVerId(),
                consumeEnable, disableRsn, resCheckEnable, allowedBClientRate,
                qryPriorityId, flowCtrlEnable, flowRuleCnt, flowCtrlInfo);
        return addGroupResCtrlConf(entity, sBuilder, result);
    }

    /**
     * Add group resource control configure info
     *
     * @param entity     the group resource control info entity will be add
     * @param sBuilder  the print info string buffer
     * @param result     the process result return
     * @return true if success otherwise false
     */
    public GroupProcessResult addGroupResCtrlConf(GroupResCtrlEntity entity,
                                                  StringBuilder sBuilder,
                                                  ProcessResult result) {
        if (metaStoreService.getGroupResCtrlConf(entity.getGroupName()) != null) {
            result.setFailResult(DataOpErrCode.DERR_EXISTED.getCode(),
                    DataOpErrCode.DERR_EXISTED.getDescription());
        } else {
            metaStoreService.addGroupResCtrlConf(entity, sBuilder, result);
        }
        return new GroupProcessResult(entity.getGroupName(), null, result);
    }

    /**
     * Update group resource control configure
     *
     * @return true if success otherwise false
     */
    public GroupProcessResult updGroupResCtrlConf(BaseEntity opEntity, String groupName,
                                                  Boolean consumeEnable, String disableRsn,
                                                  Boolean resCheckEnable, int allowedBClientRate,
                                                  int qryPriorityId, Boolean flowCtrlEnable,
                                                  int flowRuleCnt, String flowCtrlInfo,
                                                  StringBuilder sBuilder, ProcessResult result) {
        GroupResCtrlEntity curEntity =
                metaStoreService.getGroupResCtrlConf(groupName);
        if (curEntity == null) {
            result.setFailResult(DataOpErrCode.DERR_NOT_EXIST.getCode(),
                    DataOpErrCode.DERR_NOT_EXIST.getDescription());
            return new GroupProcessResult(groupName, null, result);
        }
        GroupResCtrlEntity newEntity = curEntity.clone();
        newEntity.updBaseModifyInfo(opEntity);
        if (newEntity.updModifyInfo(opEntity.getDataVerId(), consumeEnable, disableRsn,
                resCheckEnable, allowedBClientRate, qryPriorityId, flowCtrlEnable,
                flowRuleCnt, flowCtrlInfo)) {
            metaStoreService.updGroupResCtrlConf(newEntity, sBuilder, result);
        } else {
            result.setFailResult(DataOpErrCode.DERR_UNCHANGED.getCode(),
                    DataOpErrCode.DERR_UNCHANGED.getDescription());
        }
        return new GroupProcessResult(groupName, null, result);
    }

    /**
     * Delete group resource control configure
     *
     * @param operator   operator
     * @param groupNames the group will be deleted
     * @param strBuffer  the print info string buffer
     * @param result     the process result return
     * @return true if success otherwise false
     */
    public List<GroupProcessResult> delGroupResCtrlConf(String operator,
                                                        Set<String> groupNames,
                                                        StringBuilder strBuffer,
                                                        ProcessResult result) {
        List<GroupProcessResult> retInfo = new ArrayList<>();
        if (groupNames == null || groupNames.isEmpty()) {
            return retInfo;
        }
        for (String groupName : groupNames) {
            metaStoreService.delGroupResCtrlConf(operator, groupName, strBuffer, result);
            retInfo.add(new GroupProcessResult(groupName, null, result));
            strBuffer.delete(0, strBuffer.length());
            result.clear();
        }
        return retInfo;
    }

    public Map<String, GroupResCtrlEntity> confGetGroupResCtrlConf(Set<String> groupSet,
                                                                   GroupResCtrlEntity qryEntity) {
        return metaStoreService.getGroupResCtrlConf(groupSet, qryEntity);
    }

    public GroupResCtrlEntity confGetGroupResCtrlConf(String groupName) {
        return this.metaStoreService.getGroupResCtrlConf(groupName);
    }

    public GroupProcessResult addGroupConsumeCtrlInfo(BaseEntity opInfoEntity, String groupName,
                                                      String topicName, Boolean enableCsm,
                                                      String disableRsn, Boolean enableFilter,
                                                      String filterCondStr, StringBuilder sBuilder,
                                                      ProcessResult result) {
        GroupConsumeCtrlEntity entity =
                new GroupConsumeCtrlEntity(opInfoEntity, groupName, topicName);
        entity.updModifyInfo(opInfoEntity.getDataVerId(),
                enableCsm, disableRsn, enableFilter, filterCondStr);
        return addGroupConsumeCtrlInfo(entity, sBuilder, result);
    }

    public GroupProcessResult addGroupConsumeCtrlInfo(GroupConsumeCtrlEntity entity,
                                                      StringBuilder sBuilder,
                                                      ProcessResult result) {
        if (!addIfAbsentGroupResConf(entity, entity.getGroupName(), sBuilder, result)) {
            return new GroupProcessResult(entity.getGroupName(), entity.getTopicName(), result);
        }
        if (metaStoreService.getConsumeCtrlByGroupAndTopic(
                entity.getGroupName(), entity.getTopicName()) != null) {
            result.setFailResult(DataOpErrCode.DERR_EXISTED.getCode(),
                    DataOpErrCode.DERR_EXISTED.getDescription());
        } else {
            metaStoreService.addGroupConsumeCtrlConf(entity, sBuilder, result);
        }
        return new GroupProcessResult(entity.getGroupName(), entity.getTopicName(), result);
    }

    public GroupProcessResult modGroupConsumeCtrlInfo(BaseEntity opEntity, String groupName,
                                                      String topicName, Boolean enableCsm,
                                                      String disableRsn, Boolean enableFilter,
                                                      String filterCondStr, StringBuilder sBuilder,
                                                      ProcessResult result) {
        GroupConsumeCtrlEntity curEntity =
                metaStoreService.getConsumeCtrlByGroupAndTopic(groupName, topicName);
        if (curEntity == null) {
            result.setFailResult(DataOpErrCode.DERR_NOT_EXIST.getCode(),
                    DataOpErrCode.DERR_NOT_EXIST.getDescription());
            return new GroupProcessResult(groupName, topicName, result);
        }
        GroupConsumeCtrlEntity newEntity = curEntity.clone();
        newEntity.updBaseModifyInfo(opEntity);
        if (newEntity.updModifyInfo(opEntity.getDataVerId(),
                enableCsm, disableRsn, enableFilter, filterCondStr)) {
            metaStoreService.updGroupConsumeCtrlConf(newEntity, sBuilder, result);
        } else {
            result.setFailResult(DataOpErrCode.DERR_UNCHANGED.getCode(),
                    DataOpErrCode.DERR_UNCHANGED.getDescription());
        }
        return new GroupProcessResult(groupName, topicName, result);
    }

    public GroupProcessResult modGroupConsumeCtrlInfo(GroupConsumeCtrlEntity entity,
                                                      StringBuilder sBuilder,
                                                      ProcessResult result) {
        GroupConsumeCtrlEntity curEntity =
                metaStoreService.getConsumeCtrlByGroupAndTopic(
                        entity.getGroupName(), entity.getTopicName());
        if (curEntity == null) {
            result.setFailResult(DataOpErrCode.DERR_NOT_EXIST.getCode(),
                    DataOpErrCode.DERR_NOT_EXIST.getDescription());
            return new GroupProcessResult(entity.getGroupName(), entity.getTopicName(), result);
        }
        if (!addIfAbsentGroupResConf(entity, entity.getGroupName(), sBuilder, result)) {
            return new GroupProcessResult(entity.getGroupName(), entity.getTopicName(), result);
        }
        metaStoreService.updGroupConsumeCtrlConf(entity, sBuilder, result);
        return new GroupProcessResult(entity.getGroupName(), entity.getTopicName(), result);
    }

    public List<GroupProcessResult> delGroupConsumeCtrlConf(String operator,
                                                            Set<String> groupNameSet,
                                                            Set<String> topicNameSet,
                                                            StringBuilder strBuffer,
                                                            ProcessResult result) {
        List<GroupProcessResult> retInfo = new ArrayList<>();
        if ((groupNameSet == null || groupNameSet.isEmpty())
                && (topicNameSet == null || topicNameSet.isEmpty())) {
            return retInfo;
        }
        Set<String> rmvRecords = new HashSet<>();
        if (topicNameSet != null && !topicNameSet.isEmpty()) {
            rmvRecords.addAll(metaStoreService.getConsumeCtrlKeyByTopicName(topicNameSet));
        }
        if (groupNameSet != null && !groupNameSet.isEmpty()) {
            rmvRecords.addAll(metaStoreService.getConsumeCtrlKeyByGroupName(groupNameSet));
        }
        GroupProcessResult retItem;
        for (String recKey : rmvRecords) {
            Tuple2<String, String> groupTopicTuple =
                    KeyBuilderUtils.splitRecKey2GroupTopic(recKey);
            metaStoreService.delGroupConsumeCtrlConf(operator, recKey, strBuffer, result);
            retItem = new GroupProcessResult(groupTopicTuple.getF0(),
                    groupTopicTuple.getF1(), result);
            retInfo.add(retItem);
            result.clear();
        }
        return retInfo;
    }

    private boolean addIfAbsentGroupResConf(BaseEntity opEntity, String groupName,
                                            StringBuilder sBuilder, ProcessResult result) {
        GroupResCtrlEntity resCtrlEntity =
                this.metaStoreService.getGroupResCtrlConf(groupName);
        if (resCtrlEntity != null) {
            result.setSuccResult(null);
            return true;
        }
        resCtrlEntity = new GroupResCtrlEntity(opEntity, groupName);
        resCtrlEntity.setGroupName(groupName);
        resCtrlEntity.fillDefaultValue();
        return this.metaStoreService.addGroupResCtrlConf(resCtrlEntity, sBuilder, result);
    }

    /**
     * Query group consume control configure by query entity
     *
     * @param qryEntity the entity to get matched condition,
     *                  may be null, will return all group filter condition
     * @return group consume control list
     */
    public List<GroupConsumeCtrlEntity> confGetGroupConsumeCtrlConf(
            GroupConsumeCtrlEntity qryEntity) {
        return metaStoreService.getGroupConsumeCtrlConf(qryEntity);
    }

    /**
     * Get all group consume control record for a specific topic
     *
     * @param topicName
     * @return group consume control list
     */
    public List<GroupConsumeCtrlEntity> getConsumeCtrlByTopic(String topicName) {
        return metaStoreService.getConsumeCtrlByTopicName(topicName);
    }

    /**
     * Get group consume control configure for a topic & group
     *
     * @param topicName the topic name
     * @param groupName the group name
     * @return group consume control record
     */
    public GroupConsumeCtrlEntity getGroupConsumeCtrlConf(String groupName,
                                                          String topicName) {
        return metaStoreService.getConsumeCtrlByGroupAndTopic(groupName, topicName);
    }

    /**
     * Get group consume control configure for topic & group set
     *
     * @param groupNameSet the topic name
     * @param topicNameSet the group name
     * @return group consume control record
     */
    public Map<String, List<GroupConsumeCtrlEntity>> getGroupConsumeCtrlConf(
            Set<String> groupNameSet, Set<String> topicNameSet) {
        return metaStoreService.getConsumeCtrlByGroupAndTopic(groupNameSet, topicNameSet);
    }

    /**
     * Add consume group to blacklist
     *
     * @param entity     the group will be add into black list
     * @param strBuffer  the print info string buffer
     * @param result     the process result return
     * @return true if success otherwise false
     */
    public boolean confAddBlackListGroup(GroupBlackListEntity entity,
                                         StringBuilder strBuffer,
                                         ProcessResult result) {
        if (metaStoreService.addGroupBlackListConf(entity, result)) {
            strBuffer.append("[confAddBlackListGroup], ")
                    .append(entity.getCreateUser())
                    .append(" added black list group record :")
                    .append(entity.toString());
            logger.info(strBuffer.toString());
        } else {
            strBuffer.append("[confAddBlackListGroup], ")
                    .append("failure to add black list group record : ")
                    .append(result.getErrInfo());
            logger.warn(strBuffer.toString());
        }
        strBuffer.delete(0, strBuffer.length());
        return result.isSuccess();
    }

    /**
     * Delete black consume group list from store
     *
     * @param operator  operator
     * @param groupName the blacklist record related to group
     * @param topicName the blacklist record related to topic
     *                  allow groupName or topicName is null,
     *                  but not all null
     * @return true if success
     */
    public boolean confDelBlackGroupConf(String operator,
                                         String groupName,
                                         String topicName,
                                         StringBuilder strBuffer,
                                         ProcessResult result) {
        if (groupName == null && topicName == null) {
            result.setSuccResult(null);
            return true;
        }
        if (metaStoreService.delGroupBlackListConf(groupName, topicName, result)) {
            strBuffer.append("[confDelBlackGroupConf], ").append(operator)
                    .append(" deleted black list group record by index : ")
                    .append("groupName=").append(groupName)
                    .append(", topicName=").append(topicName);
            logger.info(strBuffer.toString());
        } else {
            strBuffer.append("[confDelBlackGroupConf], ")
                    .append("failure to delete black list group record : ")
                    .append(result.getErrInfo());
            logger.warn(strBuffer.toString());
        }
        strBuffer.delete(0, strBuffer.length());
        return result.isSuccess();
    }

    /**
     * Get black consumer group list via query entity
     *
     * @param qryEntity  the query entity
     * @return query result
     */
    public List<GroupBlackListEntity> confGetBlackGroupInfo(
            GroupBlackListEntity qryEntity) {
        return metaStoreService.getGroupBlackListConf(qryEntity);
    }

    public Set<String> getBlkGroupTopicInfo(String groupName) {
        return metaStoreService.getGrpBlkLstNATopicByGroupName(groupName);
    }

    // //////////////////////////////////////////////////////////////////////////////


}
