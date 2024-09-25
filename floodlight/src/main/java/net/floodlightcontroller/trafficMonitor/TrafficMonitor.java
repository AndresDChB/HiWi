package net.floodlightcontroller.trafficMonitor;
 
import java.util.*;
import java.util.concurrent.*;
import java.text.DecimalFormat;
import java.io.IOException;
import java.net.Socket;
 
import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.PortChangeType;

import com.google.common.util.concurrent.ListenableFuture;

import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFStatsReply;
import org.projectfloodlight.openflow.protocol.OFFlowStatsRequest;
import org.projectfloodlight.openflow.protocol.OFFlowStatsReply;
import org.projectfloodlight.openflow.protocol.OFFlowStatsEntry;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.OFBarrierRequest;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFFlowDelete;
import org.projectfloodlight.openflow.types.*;

import net.floodlightcontroller.core.IFloodlightProviderService;

import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Poller;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.javatuples.Triplet;

import csvwriter.CSVWriter;
 
public class TrafficMonitor implements IOFMessageListener, IFloodlightModule, IOFSwitchListener {
    
    private static final int DRILLDOWN_TREX = 0;
    private static final int INSTALL_LOOP = 1;
    private static final int DEL_INST_TEST = 2;
    private static final int DRILLDOWN_TCPREPLAY = 3;

    //For drilldowns with tcpreplay
    //Pcap start delay in milliseconds
    private int tcprepBGDelay;
    private int tcprepAttackDelay;
    private int tcprepTrafficDelay;

    //For drilldowns with tcpreplay
    //Pcap data rate in mbps
    private float tcprepBGDataRate;
    private float tcprepAttackDataRate;
    private float tcprepTrafficDataRate;

    private int mode;

    //Traffic generation server host and port
    private static String trGenServerHost;
    private static int trGenServerPort;

    //Data rate at which Trex will generate traffic
    private static String dataRate; //Mbps
    
    protected IFloodlightProviderService floodlightProvider;
    protected static Logger logger;
    private IOFSwitchService switchService;

    private int res; //Subnet resolution
    private long expTimeMillis; //Exposure time in milliseconds
    
    ZMQ.Context socketContextClassifier = ZMQ.context(1);
    ZMQ.Socket socketClassifier = socketContextClassifier.socket(ZMQ.REQ);

    ZMQ.Context socketContextTrGen = ZMQ.context(1);
    ZMQ.Socket socketTrGen = socketContextTrGen.socket(ZMQ.REQ);

    List<Triplet<Masked<IPv4Address> , Masked<IPv4Address> , U64>> totalMatchesAndCounts = 
    new ArrayList<Triplet<Masked<IPv4Address> , Masked<IPv4Address> , U64>>();

    //Set to true if connection with the python CNN app has been established
    private boolean classifierConnected = false;
    //Set to true if connection with the other server's traffic generation app has been established
    private boolean trGenConnected = false;

    private long sendingTime = 0; //Timestamp of the moment when flow rules are sent
    private long deletionTime = 0; //Timestamp of the moment when a flow deletion request is sent
    private long ddStart = 0; //Timestamp of the moment a drilldown starts

    //Barrier replies dont explain which action caused them
    //These flags are set by flow installation and deletion operations 
    //so that when a barrier reply arrives we can know what caused it
    private boolean flowsSent = false;
    private boolean deleteMsgSent = false;
    
    private int measurements; //Number of measurements taken
    private boolean write; //If the measurements are written to a csv or not

    //Set to true when the subnet granularity cannot become higher inidicating
    //the end of the drilldown
    private boolean drillDownEnded = false; 


    //For sending messages to the switch, classifier and traffic gen server
    //in another thread to prevent blocking the other controller functions
    ExecutorService executor = Executors.newFixedThreadPool(1);

    //Stores the initial subnets of the resolution prior to a drilldown
    private String[] initialSubnets;
 
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IFloodlightProviderService.class);
        l.add(IOFSwitchService.class);
        return l;
    }
    
    @Override
    public void init(FloodlightModuleContext context)
            throws FloodlightModuleException {

        Map<String, String> configParams = context.getConfigParams(this);

        res = Integer.parseInt(configParams.get("res"));
        expTimeMillis = Long.parseLong(configParams.get("expTimeMillis"));
        dataRate = configParams.get("dataRate");
        tcprepBGDelay = Integer.parseInt(configParams.get("tcprepBGDelay"));
        tcprepAttackDelay = Integer.parseInt(configParams.get("tcprepAttackDelay"));
        tcprepTrafficDelay = Integer.parseInt(configParams.get("tcprepTrafficDelay"));
        tcprepBGDataRate = Integer.parseInt(configParams.get("tcprepBGDataRate"));
        tcprepAttackDataRate = Integer.parseInt(configParams.get("tcprepAttackDataRate"));
        tcprepTrafficDataRate = Integer.parseInt(configParams.get("tcprepTrafficDataRate"));
        tcprepBGDataRate = Integer.parseInt(configParams.get("tcprepBGDataRate"));
        trGenServerPort = Integer.parseInt(configParams.get("trGenServerPort"));
        measurements = Integer.parseInt(configParams.get("measurements"));
        write = Boolean.parseBoolean(configParams.get("write"));
        mode = Integer.parseInt(configParams.get("mdoe"));
        
        boolean serverAvailableClassifier = isServerAvailable("localhost", 5555);
        if (serverAvailableClassifier) {
            System.out.println("Connected to classifier");
            classifierConnected = true;
        }

        boolean serverAvailableTrGen = isServerAvailable(trGenServerHost, trGenServerPort);
        if (serverAvailableTrGen) {
            System.out.println("Connected to traffic generation server");
            trGenConnected = true;
        }
        
        socketClassifier.connect("tcp://localhost:5555");
        socketTrGen.connect("tcp://" + trGenServerHost + ":" + trGenServerPort);

        int maskBits = (int) (Math.log(res) / Math.log(2));
        initialSubnets = IpAddrGenerator.generateIps(maskBits);
        floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        switchService = context.getServiceImpl(IOFSwitchService.class);
        logger = LoggerFactory.getLogger(TrafficMonitor.class);
        
        logger.info("Rule installer initiated");
        addShutdownHook();
    }

    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown hook triggered. Shutting down executor...");
            // Clean up
            socketClassifier.close();
            socketContextClassifier.term();
            socketTrGen.close();
            socketContextTrGen.term();
            executor.shutdownNow(); // This interrupts running tasks
            System.out.println("Shutdown complete.");
        }));
    }
 
    @Override
    public void startUp(FloodlightModuleContext context) {
        this.floodlightProvider.addOFMessageListener(OFType.BARRIER_REPLY, this);
        this.switchService.addOFSwitchListener(this);
    }
    
    private void measureLatency(long timeStamp, String fileName, String description, double subtraction) {
        long receptionTime = System.nanoTime();
        long timeNano = receptionTime - timeStamp;
        double timeSeconds = (double)timeNano/1000000000;
        timeSeconds -= subtraction;
        logger.info("{} done after {} seconds", description, timeSeconds);

        DecimalFormat decimalFormat = new DecimalFormat("#,##0.000000");
        String timeString = decimalFormat.format(timeSeconds);
        String[] resAndInstLat = new String[]{String.valueOf(res), timeString};
        String newData = CSVWriter.convertToCSV(resAndInstLat);
        try {
            CSVWriter.writeToCsv(fileName, newData, write);
        }
        catch (IOException e) {
            logger.info("CSV Writer fn exploded lfmao");
        }
    }

    @Override
    public net.floodlightcontroller.core.IListener.Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
        logger.info("Message received of type: {}", msg.getType());

        if (flowsSent) {
            measureLatency(sendingTime, 
                "/home/borja/HiWi/floodlight/results/idle/installation_latency.csv", 
                "Flow installation",
                0);
            flowsSent = false;
        }

        if (deleteMsgSent) {
            measureLatency(
                deletionTime,
                "/home/borja/HiWi/floodlight/results/idle/deletion_latency.csv",
                "Flow deletion",
                0
            );
            deleteMsgSent = false;
        }

        return Command.CONTINUE; // Continue with the next listener
    }

    @Override 
    public void switchAdded(DatapathId switchId){
        logger.info("Switch added");
        Runnable flowInstRunnable = new Runnable() {
            @Override
            public void run() {

                switch (mode) {
                    case DRILLDOWN_TREX:
                        drillDownTest(switchId);
                        break;    
                    case DRILLDOWN_TCPREPLAY:
                        drillDownTest(switchId);
                        break;
                    case INSTALL_LOOP:
                        flowInstallLoop(switchId);
                        break;
                    case DEL_INST_TEST:
                        installationAndDeletionTest(switchId);
                        break;
                    default:
                        logger.error("Execution mode unknown");
                }
            }
        };
        Future<?> future = executor.submit(flowInstRunnable); 
    }
    /*
    *Delete flows rules, wait, install new rules, delete them again
    *This function checks the correct installation and deletion by 
    *making a stats request and comparing the number of replies to
    *their expected number
    */ 
    private void installationAndDeletionTest(DatapathId switchId) {
        IOFSwitch sw = switchService.getSwitch(switchId);

                //Verify initial deletion
                deleteAllFlows(switchId);
                
                try{
                    TimeUnit.SECONDS.sleep(7);
                } catch (InterruptedException e) {
                    logger.info("Sleep interrupted");
                }

                ListenableFuture<?> future = sendFlowStatsRequest(switchId);
                getStats(sw, future);
                System.out.println("Expected Rules: 0");
                System.out.println("Number of installed flow rules: " + totalMatchesAndCounts.size());

                List<OFMessage> flowMods = createMatches(initialSubnets, initialSubnets, sw);
                installFlows(switchId, flowMods);
                //Verify installation
                future = sendFlowStatsRequest(switchId);
                getStats(sw, future);
                System.out.println("Expected Rules: " + Math.pow(res, 2));
                System.out.println("Number of installed flow rules: " + totalMatchesAndCounts.size());
                //Verify deletion
                deleteAllFlows(switchId);
                future = sendFlowStatsRequest(switchId);
                getStats(sw, future);
                System.out.println("Expected Rules: 0");
                System.out.println("Number of installed flow rules: " + totalMatchesAndCounts.size());
    }

    /*
     * Performs a series of drilldowns determined by the "measurements"
     * class variable. 
     * This method measures the following latencies:
     * -Flow installation -Flow deletion
     * -Complete drilldown -Drilldown iteration
     * 
     * The classification latency is measured by the classifier python app
     */
    private void drillDownTest(DatapathId switchId) {
        String subnetBase = "0.0.0.0"; //Base for the drilldown's mock subnet
        int subnetMask = 0;

        int waitTime = 3;

        sleepSeconds(waitTime, "Initial wait time interrupted");
      
        logger.info("Drilldown started");
        
        Poller trGenPoller = socketContextTrGen.poller(1); // Poller for 1 socket
        trGenPoller.register(socketTrGen, Poller.POLLIN);

        if (trGenConnected) {
            sendTrMessage("start", String.valueOf(res), String.valueOf(write), dataRate, "0", String.valueOf(measurements));
            // Wait for the reply from the server
            System.out.println("Waiting for response");
            waitTrReply(trGenPoller);
        }
        
        for (int i = 0; i < measurements; i++) {
            int ddStep = 1;

            ddStart = System.nanoTime();
            while (!drillDownEnded) {

                //Drilldown
                long ddIterStart = System.nanoTime();
                String subnet = subnetBase + "/" + String.valueOf(subnetMask); //Mock subnet
                logger.info("Drilling down into subnet {}", subnet);
                drillDown(subnet, switchId);
                subnetMask += (int) (Math.log(res)/Math.log(2));
                
                //Wait exp time
                sleepMillis(expTimeMillis, "Exposure time interrupted");

                //Get stats
                if (!drillDownEnded) {
                    
                    //TODO maybe do this in another thread
                    ListenableFuture<?> future = sendFlowStatsRequest(switchId);
                    IOFSwitch sw = switchService.getSwitch(switchId);
                    getStats(sw, future);
                    long[][] aggregationMap = createAggregationMap(totalMatchesAndCounts);
                    //printAggMap(aggregationMap);
                            
                    System.out.println("Number of matches and counts: " + totalMatchesAndCounts.size());

                    if (classifierConnected) {
                        JSONObject jsonMessage = classifierJSON(aggregationMap);
                        String jsonString = jsonMessage.toString();
                        System.out.println("Sending agg map to classifier");
                        socketClassifier.send(jsonString.getBytes(ZMQ.CHARSET), 0);
                        System.out.println("Sending done");
                    }
                    
                    // Wait for the reply from the server
                    Poller poller = socketContextClassifier.poller(1); // Poller for 1 socket
                    poller.register(socketClassifier, Poller.POLLIN);

                    System.out.println("Waiting for response");
                    // Poll for events
                    poller.poll(5000); // Timeout in milliseconds

                    if (poller.pollin(0)) {
                        // Receive message if available
                
                        String message = socketClassifier.recvStr(0);
                        if (message != null) {
                            System.out.println("Received: " + message); 
                            //TODO process classifier response
                            double subtraction = (double) expTimeMillis / 1000; 
                            String description = "Drilldown iteration " + ddStep;
                            measureLatency(ddIterStart, 
                            "/home/borja/HiWi/floodlight/results/idle/dd_iteration.latency.csv", 
                            description,
                            subtraction
                            );

                        }
                    }
                }
                ddStep++;
            }
            ddStep --; //Remove last increment as the last iteration only detects whether the drilldown has ended
            
            measureLatency(ddStart, 
                "/home/borja/HiWi/floodlight/results/idle/dd_latency_with_classififcation.csv",
                "Drilldown",
                ddStep);
            
            sleepSeconds(1, "Post drilldown wait time interrupted");

            subnetMask = 0;
            drillDownEnded = false;

            sleepSeconds(1, "Post drilldown wait time interrupted");
        }

        if (trGenConnected) {
            sendTrMessage("stop", String.valueOf(res), String.valueOf(write), dataRate, "0", String.valueOf(measurements));
            // Wait for the reply from the server

            System.out.println("Waiting for response");
            waitTrReply(trGenPoller);
        }
    }

    //Installs and uninstalls flow rules in a loop to gather install latency information
    private void flowInstallLoop(DatapathId switchId) {

        int waitTime = 3;
        try {
            TimeUnit.SECONDS.sleep(waitTime); 
        }
        catch(InterruptedException e) { 
            logger.error("Initial wait time failed");
        }

        for (int i = 0; i < measurements; i++) {
            deleteAllFlows(switchId);
            //Wait for flow deletion
            try {
                TimeUnit.SECONDS.sleep(waitTime); 
            }
            catch(InterruptedException e) { 
                logger.error("Wait time after deleting failed");
            }

            IOFSwitch sw = switchService.getSwitch(switchId);
            List<OFMessage> flowMods = createMatches(initialSubnets, initialSubnets, sw);
            installFlows(switchId, flowMods);

            //Wait for flow installation
            try {
                TimeUnit.SECONDS.sleep(waitTime); 
            }
            catch(InterruptedException e) {
                logger.error("Wait time after installing failed");
            }
        }
        logger.info("Measurements done");
    }
    
    public void deleteAllFlows(DatapathId switchId) {
        IOFSwitch sw = switchService.getSwitch(switchId);
        if (sw == null) {
            logger.error("Switch {} not found!", switchId);
            return;
        }
    
        OFFactory factory = sw.getOFFactory();
        
        // Create a match that matches all flows (wildcard match)
        Match match = factory.buildMatch().build();
    
        // Build the flow delete message
        OFFlowDelete flowDelete = factory.buildFlowDelete()
                .setMatch(match)
                .setTableId(TableId.ALL) // Apply to all tables
                .setOutPort(OFPort.ANY)  // Match any output port
                .setOutGroup(OFGroup.ANY) // Match any group
                .setCookie(U64.ZERO) // Match all cookies
                .setCookieMask(U64.ZERO) // Don't mask cookie
                .setBufferId(OFBufferId.NO_BUFFER)
                .build();
    
        // Send the flow delete message to the switch

        deletionTime = System.nanoTime();

        sw.write(flowDelete);
        logger.info("Sent delete all flows command to switch {}", switchId);
        OFBarrierRequest barrierRequest = sw.getOFFactory().buildBarrierRequest().build();
        deleteMsgSent = true;
        sw.write(barrierRequest);
        
    }

    private List<OFMessage> createMatches(String[] srcIPs, String[] dstIPs, IOFSwitch sw) {
        logger.info("Creaing matches");
        List<OFMessage> flowMods = new ArrayList<>();
        OFFactory ofFactory = sw.getOFFactory();

        for (String IPSrc : srcIPs) {
            for (String IPDst : dstIPs){
                OFFlowAdd newFlow = ofFactory.buildFlowAdd()
                    .setMatch(ofFactory.buildMatch()
                            .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                            .setExact(MatchField.IN_PORT, OFPort.of(17))
                            .setMasked(MatchField.IPV4_SRC, IPv4AddressWithMask.of(IPSrc))
                            .setMasked(MatchField.IPV4_DST, IPv4AddressWithMask.of(IPDst))
                            .build())
                    .setActions(Collections.singletonList(
                            ofFactory.actions().output(OFPort.of(19), Integer.MAX_VALUE)
                    ))
                    .setPriority(100)
                    .setBufferId(OFBufferId.NO_BUFFER)
                    .setHardTimeout(0)
                    .setIdleTimeout(0)
                    .setCookie(U64.of(0))
                    .build();

                flowMods.add(newFlow);
            }
        }

        return flowMods;
    }

    //installs the flow rules of a given resolution
    //TODO higher resolutions lead to table full errors
    public void installFlows(DatapathId switchId, List<OFMessage> flowMods){
        
        IOFSwitch sw = switchService.getSwitch(switchId);
        
        if (sw == null) {
            throw new RuntimeException("Switch " + switchId + " not found!");
        }

        int flowBatchSize = flowMods.size();

        logger.info("Flowbatch size: " + flowBatchSize);

        List<List<OFMessage>> batches = splitList(flowMods);

        sendingTime = System.nanoTime();


        System.out.println("Number of batches: " + batches.size());
        for (List<OFMessage> batch : batches) {
            System.out.println("Batch size: " + batch.size());
            sw.write(batch);
        }
        logger.info("Flow rules sent to {}, sending time: {}", switchId, sendingTime);
        OFBarrierRequest barrierRequest = sw.getOFFactory().buildBarrierRequest().build();
        flowsSent = true;
        sw.write(barrierRequest);

    }

    /*Returns true while the max depth has not been reached
    *@subnet destination subnet where the drill down occurs
    *
    */
    private void drillDown(String subnet, DatapathId switchId) {
        //We'll assume the subnet is correct
        IOFSwitch sw = switchService.getSwitch(switchId);
        String[] subnetParts = subnet.split("/");
        String baseIp = subnetParts[0];
        int originalMask = Integer.parseInt(subnetParts[1]);

        if (originalMask + (int) (Math.log(res) / Math.log(2)) > 32) {
            logger.info("Max drilldown depth reached");
            drillDownEnded = true;
            return;
        }
        deleteAllFlows(switchId);

        logger.info("Creating drilldown flow rules");
        //For now drill down the first subnet
        String[] drillDownSubnets = IpAddrGenerator.drillDown(res, subnet);

        List<OFMessage> drillDownFlows = createMatches(initialSubnets, drillDownSubnets, sw);
        logger.info("Drilldown rules created");
        installFlows(switchId, drillDownFlows);
        
    }
    
    //Separate this into different functions
    private ListenableFuture<?> sendFlowStatsRequest(DatapathId switchId) {
        IOFSwitch sw = switchService.getSwitch(switchId);
        ListenableFuture<?> future;

        Match match = sw.getOFFactory().buildMatch()
        .setExact(MatchField.ETH_TYPE, EthType.IPv4)
        .build();

        OFFlowStatsRequest request = sw.getOFFactory().buildFlowStatsRequest()
        .setMatch(match)
        .setTableId(TableId.ALL) // Query all tables
        .setOutPort(OFPort.ANY) // Match any output port
        .setOutGroup(OFGroup.ANY) // Match any group
        .build();

        future = sw.writeStatsRequest(request);

        return future;
        
        /*

        //TODO remove this, this is to check the matches and counts list's correct functionality
        for(Triplet<Masked<IPv4Address> , Masked<IPv4Address> , U64> matchAndCount : totalMatchesAndCounts) {

            Masked<IPv4Address> srcIp = matchAndCount.getValue0();
            Masked<IPv4Address> dstIp = matchAndCount.getValue1();

            IPv4Address srcIpAddr = srcIp.getValue();
            IPv4Address dstIpAddr = dstIp.getValue();

            int srcIpMask = srcIp.getMask().asCidrMaskLength();
            int dstIpMask = dstIp.getMask().asCidrMaskLength();

            logger.info("SrcIP: {}/{}, DstIP: {}/{}, PktCount: {}", 
                srcIpAddr, srcIpMask,
                dstIpAddr, dstIpMask,
                matchAndCount.getValue2());
        }

        */

    }

    private void sendTrexMessage(String state, String resolution, String write, String dataRate, String duration, String iterations) {
        JSONObject message = new JSONObject();

        try {
            message.put("state", state);
            message.put("resolution", resolution);
            message.put("write", write);
            message.put("data_rate", dataRate);
            message.put("duration", duration);
            message.put("iterations", iterations);
            
        } catch(JSONException e) {
            System.out.println(e);
        }

        String jsonString = message.toString();
        System.out.println("Sending message to trx traffic generation server");
        socketTrGen.send(jsonString.getBytes(ZMQ.CHARSET), ZMQ.NOBLOCK);
    }

    private void sendTcpreplayMessasge(String state, String resolution, String write, String duration) {
        JSONObject message = new JSONObject();

        try {
            message.put("state", state);
            message.put("resolution", resolution);
            message.put("write", write);

            message.put("background_delay", tcprepBGDelay);
            message.put("attack_delay", tcprepAttackDelay);
            message.put("traffic_delay", tcprepTrafficDelay);
            message.put("background_data_rate", tcprepBGDataRate);
            message.put("attack_data_rate", tcprepAttackDataRate);
            message.put("traffic_data_rate", tcprepTrafficDataRate);
            
        } catch(JSONException e) {
            System.out.println(e);
        }

        String jsonString = message.toString();
        System.out.println("Sending message to tcpreplay traffic generation server");
        socketTrGen.send(jsonString.getBytes(ZMQ.CHARSET), ZMQ.NOBLOCK);
    }

    private void sendTrMessage(String state, String resolution, String write, String dataRate, String duration, String iterations) {
        switch (mode) {
            case DRILLDOWN_TCPREPLAY:
                sendTcpreplayMessasge(state, resolution, write, duration);
                break;
            case DRILLDOWN_TREX:
                sendTrexMessage(state, resolution, write, dataRate, duration, iterations);
                break;
            default:
                System.out.println("Error: Method called unexpectedly");
        }

        
    }
    
    private void getStats(IOFSwitch sw, ListenableFuture<?> future){
        List<OFStatsReply> values = null;
        
        try {
            values = (List<OFStatsReply>) future.get(5, TimeUnit.SECONDS);
            totalMatchesAndCounts.clear();
            logger.info("Stats received");
            //In case this getStats() get called multiple times before having all rules
            //clear only after all matches have been received
            //int expectedReplies = (int) Math.pow(res, 2);
            
        }
        catch (Exception e){
            logger.error("Failure retrieving statistics from switch {}. {}", sw, e);
        }
            

        // Iterate over the received stats replies
        for (OFStatsReply reply : values) {
            if (reply instanceof OFFlowStatsReply) {
                OFFlowStatsReply flowStatsReply = (OFFlowStatsReply) reply;
            
                // Iterate over each flow stats entry in the reply
                for (OFFlowStatsEntry entry : flowStatsReply.getEntries()) {
                    Match currentMatch = entry.getMatch();
                    Iterable<MatchField<?>> matchFields = currentMatch.getMatchFields();
                    /*for (MatchField<?> field : matchFields) {
                        logger.info(field.getName());
                    }*/
            
                    Masked<IPv4Address> srcIp = null;
                    Masked<IPv4Address> dstIp = null;
                    try {
                        srcIp = currentMatch.getMasked(MatchField.IPV4_SRC);
                    }
                    catch (UnsupportedOperationException e)
                    {
                        IPv4Address address = currentMatch.get(MatchField.IPV4_SRC);
                        IPv4Address mask = IPv4Address.NO_MASK;
                        srcIp = Masked.of(address, mask);
                    }

                    try {
                        dstIp = currentMatch.getMasked(MatchField.IPV4_DST);
                    }
                    catch (UnsupportedOperationException e)
                    {
                        IPv4Address address = currentMatch.get(MatchField.IPV4_DST);
                        IPv4Address mask = IPv4Address.NO_MASK;
                        dstIp = Masked.of(address, mask);
                    }
                    
                                        
                    U64 packetCount = entry.getPacketCount();

                    Triplet<Masked<IPv4Address> , Masked<IPv4Address> , U64> matchAndCount = 
                        Triplet.with(srcIp, dstIp, packetCount);
                    totalMatchesAndCounts.add(matchAndCount);
                }
            }
        }   
        

        //TODO calculate packet count deltas or maybe not, when drilling down you get the packet count of new flow rules

        Collections.sort(totalMatchesAndCounts, new TripletComparator());
        System.out.println("Received " + totalMatchesAndCounts.size() + " stats");
    }

    private long[][] createAggregationMap(List<Triplet<Masked<IPv4Address> , Masked<IPv4Address> , U64>> matchesAndCounts) {
        
        long[][] aggregationMap = new long[res][res];

        for (int i = 0; i < res; i++){
            for (int j = 0; j < res; j++) {
                aggregationMap[i][j] = matchesAndCounts.get(i * res + j).getValue2().getValue();
            }
        }

        return aggregationMap;

    }

    private void printAggMap(long[][] aggMap) {
        // Loop through each row
        for (long[] row : aggMap) {
            // Loop through each column in the row
            for (long element : row) {
                // Print each element followed by a space
                System.out.print(element + " ");
            }
            // Move to the next line after printing the row
            System.out.println();
        }
    }

    private void sleepSeconds(long timeout, String message) {
        try {
            TimeUnit.SECONDS.sleep(timeout); 
        }
        catch(InterruptedException e) { 
            logger.error(message);
        }
    }

    private void sleepMillis(long timeout, String message) {
        try {
            TimeUnit.MILLISECONDS.sleep(timeout);
        }
        catch(InterruptedException e) {
            logger.info(message);
        }
    }

    private <T> List<List<T>> splitList(List<T> originalList) {
        List<List<T>> sublists = new ArrayList<>();
        int size = originalList.size();
        int chunkSize = (res == 64) ? 4096 : 1024;
        
        for (int i = 0; i < size; i += chunkSize) {
            sublists.add(new ArrayList<>(originalList.subList(i, Math.min(size, i + chunkSize))));
        }
        
        return sublists;
    }

    private void waitTrReply(Poller trGenPoller) {
          // Poll for events
          trGenPoller.poll(5000); // Timeout in milliseconds

          if (trGenPoller.pollin(0)) {
              // Receive message if available
      
              String message = socketTrGen.recvStr(0);
              if (message != null) {
                  System.out.println("Received: " + message);
              }
          }
    }

    private JSONObject classifierJSON(long[][] aggregationMap) {
        JSONArray aggMapJSON = new JSONArray();
        for (long[] row : aggregationMap) {
            JSONArray rowArray = new JSONArray();
            for (long value : row) {
                rowArray.put(value);
            }
            aggMapJSON.put(rowArray);
        }

        JSONObject jsonMessage = new JSONObject();

        try {
            jsonMessage.put("write", String.valueOf(write));
            jsonMessage.put("aggMap", aggMapJSON);
        } catch (JSONException e) {
            System.out.println("Creation of JSON object failed: " + e);
        }

        return jsonMessage;
    }

    static class TripletComparator implements Comparator<Triplet<Masked<IPv4Address>, Masked<IPv4Address>, U64>> {
        @Override
        public int compare(Triplet<Masked<IPv4Address>, Masked<IPv4Address>, U64> t1,
                           Triplet<Masked<IPv4Address>, Masked<IPv4Address>, U64> t2) {

            IPv4Address ip1_1 = t1.getValue0().getValue();
            IPv4Address ip1_2 = t2.getValue0().getValue();

            // Compare first IPv4 address
            int cmp = ip1_1.compareTo(ip1_2);
            if (cmp != 0) {
                return cmp;
            }

            IPv4Address ip2_1 = t1.getValue1().getValue();
            IPv4Address ip2_2 = t2.getValue1().getValue();

            // Compare second IPv4 address
            return ip2_1.compareTo(ip2_2);
        }
    }

    private static boolean isServerAvailable(String host, int port) {
        try (Socket socket = new Socket(host, port)) {
            System.out.println("Connection to server: " + host + ":" + port + " established");
            return true;  // Connection successful
        } catch (Exception e) {
            System.out.println("Couldn't connect to server: " + host + ":" + port);
            System.out.println(e);
            return false;  // Server is not available
        }
    }

    @Override
    public void switchRemoved(DatapathId switchId){
        logger.info("Switch removed");
    }   

    @Override
    public void switchActivated(DatapathId switchId){
        logger.info("Swicth activated");
    }

    @Override
    public void switchPortChanged(DatapathId switchId,
                                  OFPortDesc port,
                                  PortChangeType type){
        
        
    }

    @Override
    public void switchChanged(DatapathId switchId){
        
    }

    @Override
    public void switchDeactivated(DatapathId switchId){
        
    }

    @Override
    public String getName() {
        return TrafficMonitor.class.getSimpleName();
    }
 
    @Override
    public boolean isCallbackOrderingPrereq(OFType type, String name) {
        //Auto-generated method stub
        return false;
    }
 
    @Override
    public boolean isCallbackOrderingPostreq(OFType type, String name) {
        //Auto-generated method stub
        return false;
    }
 
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        //Auto-generated method stub
        return null;
    }
 
    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        //Auto-generated method stub
        return null;
    }
}