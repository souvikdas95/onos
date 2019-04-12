/*
 * Copyright 2019-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.k8snetworking.impl;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IpPrefix;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.cluster.NodeId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.k8snetworking.api.K8sFlowRuleService;
import org.onosproject.k8snetworking.api.K8sNetwork;
import org.onosproject.k8snetworking.api.K8sNetworkEvent;
import org.onosproject.k8snetworking.api.K8sNetworkListener;
import org.onosproject.k8snetworking.api.K8sNetworkService;
import org.onosproject.k8snode.api.K8sNode;
import org.onosproject.k8snode.api.K8sNodeEvent;
import org.onosproject.k8snode.api.K8sNodeListener;
import org.onosproject.k8snode.api.K8sNodeService;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.driver.DriverService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.packet.PacketService;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.onlab.util.Tools.groupedThreads;
import static org.onosproject.k8snetworking.api.Constants.K8S_NETWORKING_APP_ID;
import static org.onosproject.k8snetworking.api.Constants.PRIORITY_GATEWAY_RULE;
import static org.onosproject.k8snetworking.api.Constants.ROUTING_TABLE;
import static org.onosproject.k8snetworking.util.K8sNetworkingUtil.tunnelPortNumByNetId;
import static org.onosproject.k8snetworking.util.RulePopulatorUtil.buildExtension;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Populates switching flow rules on OVS for providing the connectivity between
 * container and network gateway.
 */
@Component(immediate = true)
public class K8sSwitchingGatewayHandler {

    private final Logger log = getLogger(getClass());

    private static final int GW_IP_PREFIX = 32;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LeadershipService leadershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DriverService driverService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sFlowRuleService k8sFlowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sNetworkService k8sNetworkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sNodeService k8sNodeService;

    private final ExecutorService eventExecutor = newSingleThreadExecutor(
            groupedThreads(this.getClass().getSimpleName(), "event-handler"));
    private final InternalK8sNetworkListener k8sNetworkListener =
            new InternalK8sNetworkListener();
    private final InternalK8sNodeListener k8sNodeListener =
            new InternalK8sNodeListener();

    private ApplicationId appId;
    private NodeId localNodeId;

    @Activate
    protected void activate() {
        appId = coreService.registerApplication(K8S_NETWORKING_APP_ID);
        k8sNetworkService.addListener(k8sNetworkListener);
        k8sNodeService.addListener(k8sNodeListener);
        localNodeId = clusterService.getLocalNode().id();
        leadershipService.runForLeadership(appId.name());

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        k8sNodeService.removeListener(k8sNodeListener);
        k8sNetworkService.removeListener(k8sNetworkListener);
        leadershipService.withdraw(appId.name());
        eventExecutor.shutdown();

        log.info("Stopped");
    }

    private void setGatewayRule(K8sNetwork k8sNetwork, boolean install) {
        TrafficSelector.Builder sBuilder = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(IpPrefix.valueOf(k8sNetwork.gatewayIp(), GW_IP_PREFIX));

        for (K8sNode node : k8sNodeService.completeNodes()) {
            TrafficTreatment.Builder tBuilder = DefaultTrafficTreatment.builder();

            if (node.hostname().equals(k8sNetwork.name())) {
                tBuilder.setEthDst(node.intBridgeMac())
                        .setOutput(PortNumber.LOCAL);
            } else {
                PortNumber portNum = tunnelPortNumByNetId(k8sNetwork.networkId(),
                        k8sNetworkService, node);
                K8sNode localNode = k8sNodeService.node(k8sNetwork.name());

                tBuilder.extension(buildExtension(
                        deviceService,
                        node.intgBridge(),
                        localNode.dataIp().getIp4Address()),
                        node.intgBridge())
                        .setOutput(portNum);
            }

            k8sFlowRuleService.setRule(
                    appId,
                    node.intgBridge(),
                    sBuilder.build(),
                    tBuilder.build(),
                    PRIORITY_GATEWAY_RULE,
                    ROUTING_TABLE,
                    install);
        }
    }

    private class InternalK8sNetworkListener implements K8sNetworkListener {

        private boolean isRelevantHelper() {
            return Objects.equals(localNodeId, leadershipService.getLeader(appId.name()));
        }

        @Override
        public void event(K8sNetworkEvent event) {
            switch (event.type()) {
                case K8S_NETWORK_CREATED:
                case K8S_NETWORK_UPDATED:
                    eventExecutor.execute(() -> processNetworkCreation(event));
                    break;
                case K8S_NETWORK_REMOVED:
                    eventExecutor.execute(() -> processNetworkRemoval(event));
                    break;
                default:
                    break;
            }
        }

        private void processNetworkCreation(K8sNetworkEvent event) {
            if (!isRelevantHelper()) {
                return;
            }

            setGatewayRule(event.subject(), true);
        }

        private void processNetworkRemoval(K8sNetworkEvent event) {
            if (!isRelevantHelper()) {
                return;
            }

            setGatewayRule(event.subject(), false);
        }
    }

    private class InternalK8sNodeListener implements K8sNodeListener {
        private boolean isRelevantHelper() {
            return Objects.equals(localNodeId, leadershipService.getLeader(appId.name()));
        }

        @Override
        public void event(K8sNodeEvent event) {
            switch (event.type()) {
                case K8S_NODE_COMPLETE:
                    eventExecutor.execute(() -> processNodeCompletion(event.subject()));
                    break;
                case K8S_NODE_INCOMPLETE:
                default:
                    break;
            }
        }

        private void processNodeCompletion(K8sNode node) {
            log.info("COMPLETE node {} is detected", node.hostname());

            if (!isRelevantHelper()) {
                return;
            }

            k8sNetworkService.networks().forEach(n -> setGatewayRule(n, true));
        }
    }
}
