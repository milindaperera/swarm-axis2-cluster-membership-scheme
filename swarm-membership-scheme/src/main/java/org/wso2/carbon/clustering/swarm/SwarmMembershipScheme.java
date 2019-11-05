/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.clustering.swarm;

import com.hazelcast.config.Config;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.TcpIpConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.Member;
import com.hazelcast.core.MemberAttributeEvent;
import com.hazelcast.core.MembershipEvent;
import com.hazelcast.core.MembershipListener;
import org.apache.axis2.clustering.ClusteringFault;
import org.apache.axis2.clustering.ClusteringMessage;
import org.apache.axis2.description.Parameter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.core.clustering.hazelcast.HazelcastCarbonClusterImpl;
import org.wso2.carbon.core.clustering.hazelcast.HazelcastMembershipScheme;
import org.wso2.carbon.core.clustering.hazelcast.HazelcastUtil;
import org.wso2.carbon.utils.xml.StringUtils;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * This class contains Hazelcast membership scheme for Docker Swarm
 */
public class SwarmMembershipScheme implements HazelcastMembershipScheme {

    private static Log LOG = LogFactory.getLog(SwarmMembershipScheme.class);
    private final Map<String, Parameter> parameters;
    private final NetworkConfig nwConfig;
    private final List<ClusteringMessage> messageBuffer;

    private HazelcastInstance primaryHazelcastInstance;
    private HazelcastCarbonClusterImpl carbonCluster;
    private int localMemberPort = 4000;

    public SwarmMembershipScheme(Map<String, Parameter> parameters, String primaryDomain, Config config,
                                 HazelcastInstance primaryHazelcastInstance, List<ClusteringMessage> messageBuffer) {
        this.parameters = parameters;
        this.primaryHazelcastInstance = primaryHazelcastInstance;
        this.nwConfig = config.getNetworkConfig();
        this.messageBuffer = messageBuffer;

    }

    @Override
    public void setPrimaryHazelcastInstance(HazelcastInstance hazelcastInstance) {
        this.primaryHazelcastInstance = hazelcastInstance;
    }

    @Override
    public void setLocalMember(Member member) {
        // Nothing to do
    }

    @Override
    public void setCarbonCluster(HazelcastCarbonClusterImpl hazelcastCarbonCluster) {
        this.carbonCluster = hazelcastCarbonCluster;
    }

    @Override
    public void init() throws ClusteringFault {
        LOG.info("Initializing clustering membership scheme for Docker Swarm...");

        String localMemberPortStr = getParameterValue(SwarmConstants.LOCAL_MEMBER_PORT);
        if (!StringUtils.isEmpty(localMemberPortStr)) {
            localMemberPort = Integer.parseInt(localMemberPortStr);
        }
        configureTcpIpParameters();
        String taskListStr = getParameterValue(SwarmConstants.DOCKER_SERVICE_LIST);
        if (taskListStr == null) {
            throw new ClusteringFault("Docker service list in clustering configuration is empty");
        }
        List<String> taskList = new ArrayList<>(Arrays.asList(taskListStr.split(",")));
        addMembersFromDNS(taskList);

        LOG.info("Docker Swarm clustering membership scheme initialized successfully");
    }

    @Override
    public void joinGroup() throws ClusteringFault {
        primaryHazelcastInstance.getCluster().addMembershipListener(new SwarmMembershipSchemeListener());
    }



    private void configureTcpIpParameters() throws ClusteringFault {
        // Override hazelcast config configured by org.wso2.carbon.core.clustering.hazelcast.HazelcastClusteringAgent
        String nwInterfaceName = getParameterValue(SwarmConstants.NETWORK_INTERFACE_NAME);
        if (nwInterfaceName != null && !nwInterfaceName.isEmpty()) {
            try {
                String nwInterfaceIP = getIpAddress(nwInterfaceName);
                nwConfig.setPublicAddress(nwInterfaceIP);
            } catch (SocketException e) {
                LOG.error("Error occurred while retrieving local member host", e);
            }
        } else {
            throw new ClusteringFault("Network interface name not found in the cluster configuration");
        }

        nwConfig.getJoin().getMulticastConfig().setEnabled(false);
        nwConfig.getJoin().getAwsConfig().setEnabled(false);
        TcpIpConfig tcpIpConfig = nwConfig.getJoin().getTcpIpConfig();
        tcpIpConfig.setEnabled(true);
        Parameter connTimeoutParameter = parameters.get(SwarmConstants.CONNECTION_TIMEOUT);
        if (connTimeoutParameter != null && connTimeoutParameter.getValue() != null) {
            int connTimeout = Integer.parseInt(((String) (connTimeoutParameter.getValue())).trim());
            tcpIpConfig.setConnectionTimeoutSeconds(connTimeout);
        }
        LOG.info(String.format("Docker Swarm membership scheme TCP IP parameters configured [Connection-Timeout] %ds",
                tcpIpConfig.getConnectionTimeoutSeconds()));
    }

    private String getParameterValue(String key) {
        String value = System.getenv(key);
        if (StringUtils.isEmpty(value)) {
            value = System.getProperty(key);
        }
        if (StringUtils.isEmpty(value)) {
            Parameter parameter = parameters.get(key);
            if (parameter == null || StringUtils.isEmpty((String) parameter.getValue())) {
                return null;
            } else {
                value = (String) parameter.getValue();
            }
        }
        return value;
    }

    private void addMembersFromDNS(List<String> serviceTaskNames) throws ClusteringFault {
        for (String taskName : serviceTaskNames) {
            taskName = taskName.trim();
            LOG.info("Discovering nodes in service : " + taskName);
            InetAddress[] inetAddress = dnsLookup(taskName);
            for (InetAddress nodeAddress : inetAddress) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Node details = HostAddress:" + nodeAddress.getHostAddress() +
                                            "| HostName:" + nodeAddress.getHostName() +
                                            "| CanonicalHostName:" + nodeAddress.getCanonicalHostName());
                }
                String memberAddress = nodeAddress.getHostAddress() + ":" + localMemberPort;
                nwConfig.getJoin().getTcpIpConfig().addMember(memberAddress);
                LOG.info("Member added to the cluster : " + memberAddress);
            }
        }
    }


    private InetAddress[] dnsLookup(String dnsHost) throws ClusteringFault {
        try {
            InetAddress[]  inetAddress = InetAddress.getAllByName(dnsHost);
            LOG.info("Number of members in Docker Swarm service " + dnsHost + ": " + inetAddress.length);
            return inetAddress;
        } catch (UnknownHostException e) {
            throw new ClusteringFault ("Error occurred while DNS Lookup for container discovery", e);
        }
    }

    public static String getIpAddress(String networkInterfaceName) throws SocketException {

        Enumeration nInterfaces = NetworkInterface.getNetworkInterfaces();
        String address = "127.0.0.1"; //Default

        if (LOG.isDebugEnabled() && networkInterfaceName != null && !networkInterfaceName.isEmpty()) {
            LOG.info("Retrieving IP/Hostname of network interface for cluster communication:" + networkInterfaceName);
        }
        while (nInterfaces.hasMoreElements()) {
            NetworkInterface nInterface = (NetworkInterface) nInterfaces.nextElement();
            if (LOG.isDebugEnabled()) {
                LOG.debug("Interface : " + nInterface.getName());
            }
            if (networkInterfaceName != null && !nInterface.getName().equals(networkInterfaceName)) {
                continue;
            }
            Enumeration addresses = nInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress ip = (InetAddress) addresses.nextElement();
                if (!ip.isLoopbackAddress() && isIP(ip.getHostAddress())) {
                    LOG.info("Local host address : " + ip + "|" + ip.getHostName() + "|" + ip.getCanonicalHostName());
                    return ip.getHostAddress();
                }
            }
        }
        return address;
    }

    private static boolean isIP(String hostAddress) {
        return hostAddress.split("[.]").length == 4;
    }


    /**
     * Swarm clustering membership scheme listener
     */
    private class SwarmMembershipSchemeListener implements MembershipListener {
        @Override
        public void memberAdded(MembershipEvent membershipEvent) {
            Member member = membershipEvent.getMember();
            // Send all cluster messages
            carbonCluster.memberAdded(member);
            LOG.info(String.format("Member joined: [UUID] %s, [Address] %s", member.getUuid(),
                    member.getSocketAddress().toString()));
            // Wait for sometime for the member to completely join before
            // replaying messages
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
            }
            HazelcastUtil.sendMessagesToMember(messageBuffer, member, carbonCluster);
        }

        @Override
        public void memberRemoved(MembershipEvent membershipEvent) {
            Member member = membershipEvent.getMember();
            carbonCluster.memberRemoved(member);
            LOG.info(String.format("Member left: [UUID] %s, [Address] %s", member.getUuid(),
                    member.getSocketAddress().toString()));
        }

        @Override
        public void memberAttributeChanged(MemberAttributeEvent memberAttributeEvent) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Member attribute changed: [Key] %s, [Value] %s", memberAttributeEvent.getKey(),
                        memberAttributeEvent.getValue()));
            }
        }
    }
}
