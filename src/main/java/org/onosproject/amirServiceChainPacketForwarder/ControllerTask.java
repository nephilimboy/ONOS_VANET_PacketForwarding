package apps.amirServiceChainPacketForwarder.src.main.java.org.onosproject.amirServiceChainPacketForwarder;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IpAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.*;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.device.PortStatistics;
import org.onosproject.net.flow.*;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.host.HostService;
import org.onosproject.net.topology.TopologyService;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;


public class ControllerTask {

    public static final String MainVirtualHostIPAddress = "192.168.1.5";
    public static final String SourceIP = "10.10.0.1";
    public static final String InitialDestinationIP = "10.10.0.2";
    public static final String SecondDestinationIP = "10.10.0.3";
    //    public static final String[] WebServersName = {"app1", "app2"};
    public static final String[] WebServersName = {};
    public static final int UpThreshold = 70;

    class Task extends TimerTask {

        public Device getDevice() {
            return device;
        }

        public DeviceService getDeviceService() {
            return deviceService;
        }

        public long getDelay() {
            return delay;
        }

        // <Name, ContainerOBJ>
        private Map<String, Container> containerMap = new HashMap<String, Container>();

        public Map<String, Container> getContainerMap() {
            return containerMap;
        }

        public void setContainerMap(Map<String, Container> containerMap) {
            this.containerMap = containerMap;
        }

        // Previous Host and its flows
        private HostLocation previousLocation = null;


        public HostLocation getPreviousLocation() {
            return previousLocation;
        }

        public void setPreviousLocation(HostLocation previousLocation) {
            this.previousLocation = previousLocation;
        }


        public class Container {
            private float cpu;
            private float memory;

            public Container(float cpu, float memory) {
                this.cpu = cpu;
                this.memory = memory;
            }

            public float getCpu() {
                return cpu;
            }

            public void setCpu(float cpu) {
                this.cpu = cpu;
            }

            public float getMemory() {
                return memory;
            }

            public void setMemory(float memory) {
                this.memory = memory;
            }
        }

        @Override
        public void run() {
            while (!isExit()) {

                // Collecting The containers' CPU/Mem data from Monitoring unit
                try {
                    for (String appName : WebServersName) {
                        float tempCpu = -1;
                        float tempMemory = -1;
                        URL URLapp = new URL("http://" + MainVirtualHostIPAddress + "/" + appName);
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(URLapp.openStream()));
                        String inputLine;
                        while ((inputLine = in.readLine()) != null) {
                            if (inputLine.startsWith("Cpu")) {
                                tempCpu = Float.parseFloat(inputLine.split(" ")[1].replace("%", ""));
                            } else if (inputLine.startsWith("MemoryUsage")) {
                                tempMemory = Float.parseFloat(inputLine.split(" ")[1].replace("%", ""));
                            }
                        }
                        in.close();
                        if (tempCpu >= 0 && tempMemory >= 0) {
                            // Check the container does not exist
                            if (containerMap.get(appName) != null) {
                                containerMap.get(appName).setCpu(tempCpu);
                                containerMap.get(appName).setMemory(tempMemory);
                            } else {
                                containerMap.put(appName, new Container(tempCpu, tempMemory));
                            }
                        }
                    }
                } catch (IOException e) {
                    log.error(e.toString());
                }

                //
                if (WebServersName.length == getContainerMap().size()) {

                    log.info("----------- Here in main loop -----------");
                    // Both destination Host
                    Host initialDestinationHost = null;
                    Host secondDestinationHost = null;
                    boolean findInitHost = false;
                    boolean findSecondHost = false;


                    for (Host host : getHostService().getHostsByIp(IpAddress.valueOf(InitialDestinationIP))) {
                        initialDestinationHost = host;
                        log.info("initialDestinationHost: " + initialDestinationHost.toString());
                        findInitHost = true;
                    }
                    for (Host host : getHostService().getHostsByIp(IpAddress.valueOf(SecondDestinationIP))) {
                        secondDestinationHost = host;
                        log.info("secondDestinationHost: " + secondDestinationHost.toString());
                        findSecondHost = true;
                    }


                    // Check both destination are found
                    if (findSecondHost && findInitHost) {

//                    log.info("Both destination hosts are founded");

                        for (Host host : getHostService().getHostsByIp(IpAddress.valueOf(SourceIP))) {
//                        log.info("Source host has been founded");
                            if (getPreviousLocation() == null) {
                                setPreviousLocation(host.location());
//                            installRule(host.location(), initialDestinationHost, secondDestinationHost);
                            } else {
                                if (!getPreviousLocation().deviceId().toString().equals(host.location().deviceId().toString())) {
//                                log.info("new source host is different from previous one");
                                    // Remove rules from previous host
                                    log.info("removing rules from previous switch");
                                    for (FlowRule flowRl : previousHostFlowRules) {
                                        flowRuleService.removeFlowRules(flowRl);
                                    }
                                    previousHostFlowRules.clear();

                                    // Set the new flow on new edge switch
                                    installRule(host.location(), initialDestinationHost, secondDestinationHost);

                                    // At the end remove the previous host and add new host to it
                                    setPreviousLocation(host.location());
                                }
                            }
                        }
                    }
                }


                try {
                    Thread.sleep((getDelay() * 1000));
                    break;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }
        }
    }

    private Set<FlowRule> previousHostFlowRules = new HashSet<>();

    void installRule(HostLocation hostLocation, Host initialHostDestination, Host secondHostDestination) {
        log.info("Installing rules");
        Set<Path> paths =
                getTopologyService().getPaths(getTopologyService().currentTopology(),
                        hostLocation.deviceId(),
                        secondHostDestination.location().deviceId());

        Path mainPath = paths.stream().findFirst().get();

        // First Rule (Transmission)
        TrafficSelector selectorSending = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchInPort(hostLocation.port())
                .build();
        TrafficTreatment treatmentSending = DefaultTrafficTreatment.builder()
                .add(Instructions.modL2Dst(secondHostDestination.mac()))
                .add(Instructions.modL3Dst(IpAddress.valueOf(SecondDestinationIP)))
                .setOutput(mainPath.src().port())
                .build();
        FlowRule flowRuleSending = DefaultFlowRule.builder()
                .forDevice(hostLocation.deviceId())
                .fromApp(getAppId())
                .withSelector(selectorSending)
                .withTreatment(treatmentSending)
                .withPriority(60010)
                .makePermanent()
                .build();
        FlowRuleOperations flowRuleOperationsSending = FlowRuleOperations.builder()
                .add(flowRuleSending)
                .build();
        flowRuleService.apply(flowRuleOperationsSending);


        // Second Rule (Receiving)
        TrafficSelector selectorReceiving = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .build();
        TrafficTreatment treatmentReceiving = DefaultTrafficTreatment.builder()
                .add(Instructions.modL2Src(initialHostDestination.mac()))
                .add(Instructions.modL3Src(IpAddress.valueOf(InitialDestinationIP)))
                .setOutput(hostLocation.port())
                .build();
        FlowRule flowRuleReceiving = DefaultFlowRule.builder()
                .forDevice(hostLocation.deviceId())
                .fromApp(getAppId())
                .withSelector(selectorReceiving)
                .withTreatment(treatmentReceiving)
                .withPriority(60009)
                .makePermanent()
                .build();
        FlowRuleOperations flowRuleOperationsReceiving = FlowRuleOperations.builder()
                .add(flowRuleReceiving)
                .build();
        flowRuleService.apply(flowRuleOperationsReceiving);


        previousHostFlowRules.add(flowRuleSending);
        previousHostFlowRules.add(flowRuleReceiving);
    }


    private FlowRuleService flowRuleService;

    public FlowRuleService getFlowRuleService() {
        return flowRuleService;
    }

    public void setFlowRuleService(FlowRuleService flowRuleService) {
        this.flowRuleService = flowRuleService;
    }

    private Iterable<FlowEntry> flowEntries;

    public Iterable<FlowEntry> getFlowEntries() {
        return flowEntries;
    }

    public void setFlowEntries(Iterable<FlowEntry> flowEntries) {
        this.flowEntries = flowEntries;
    }

    private PortNumber portNumber;

    public PortNumber getPortNumber() {
        return portNumber;
    }

    public void setPortNumber(PortNumber portNumber) {
        this.portNumber = portNumber;
    }

    public void schedule() {
        this.getTimer().schedule(new Task(), 0, 1000);
    }

    public Timer getTimer() {
        return timer;
    }

    public void setTimer(Timer timer) {
        this.timer = timer;
    }

    private Timer timer = new Timer();

    public Logger getLog() {
        return log;
    }

    public void setLog(Logger log) {
        this.log = log;
    }

    private Logger log;

    public boolean isExit() {
        return exit;
    }

    public void setExit(boolean exit) {
        this.exit = exit;
    }

    private boolean exit;

    public long getDelay() {
        return delay;
    }

    public void setDelay(long delay) {
        this.delay = delay;
    }

    private long delay;

    public PortStatistics getPortStats() {
        return portStats;
    }

    public void setPortStats(PortStatistics portStats) {
        this.portStats = portStats;
    }

    private PortStatistics portStats;

    public Long getPort() {
        return port;
    }

    public void setPort(Long port) {
        this.port = port;
    }

    private Long port;

    public DeviceService getDeviceService() {
        return deviceService;
    }

    public void setDeviceService(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    protected DeviceService deviceService;

    public Device getDevice() {
        return device;
    }

    public void setDevice(Device device) {
        this.device = device;
    }


    protected HostService hostService;

    public void setHostService(HostService hostService) {
        this.hostService = hostService;
    }

    public HostService getHostService() {
        return hostService;
    }


    protected TopologyService topologyService;

    public void setTopologyService(TopologyService topologyService) {
        this.topologyService = topologyService;
    }

    public TopologyService getTopologyService() {
        return topologyService;
    }

    private ApplicationId appId;

    public ApplicationId getAppId() {
        return appId;
    }

    public void setAppId(ApplicationId appId) {
        this.appId = appId;
    }

    private Device device;
}

