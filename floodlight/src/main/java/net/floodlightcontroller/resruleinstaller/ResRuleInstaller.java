package net.floodlightcontroller.resruleinstaller;
 
import java.util.*;
import java.util.concurrent.*;
import java.text.DecimalFormat;
import java.io.IOException;
import java.net.Socket;
import java.time.Instant;
 
import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import com.google.common.util.concurrent.ListenableFuture;

import org.projectfloodlight.openflow.protocol.OFActionType;
import org.projectfloodlight.openflow.protocol.OFCapabilities;
import org.projectfloodlight.openflow.protocol.OFControllerRole;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFRequest;
import org.projectfloodlight.openflow.protocol.OFStatsReply;
import org.projectfloodlight.openflow.protocol.OFFlowStatsRequest;
import org.projectfloodlight.openflow.protocol.OFFlowStatsReply;
import org.projectfloodlight.openflow.protocol.OFFlowStatsEntry;
import org.projectfloodlight.openflow.protocol.OFStatsRequest;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.OFBarrierRequest;
import org.projectfloodlight.openflow.protocol.OFBarrierReply;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFFlowDelete;
import org.projectfloodlight.openflow.protocol.OFFlowDelete.Builder;
import org.projectfloodlight.openflow.types.*;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.packet.Ethernet;

import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Poller;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import net.floodlightcontroller.core.PortChangeType;

import org.javatuples.Triplet;

import csvwriter.CSVWriter;
 
public class ResRuleInstaller implements IOFMessageListener, IFloodlightModule, IOFSwitchListener {
    
    protected IFloodlightProviderService floodlightProvider;
    protected static Logger logger;
    private IOFSwitchService switchService;
    private int res = 16;
    private long expTimeMillis = 1000;
    //For drilldown experiments
    private int expectedBarrierReplies = (int) 32 / (int) (Math.log(res) / Math.log(2)) * 2;
    
    ZMQ.Context socketContext = ZMQ.context(1);
    ZMQ.Socket socket = socketContext.socket(ZMQ.REQ);

    List<Triplet<Masked<IPv4Address> , Masked<IPv4Address> , U64>> totalMatchesAndCounts = 
    new ArrayList<Triplet<Masked<IPv4Address> , Masked<IPv4Address> , U64>>();

    private int barrierReplies = 0;
    private boolean classifierConnected = false;
    private long sendingTime = 0;
    private long ddStart = 0;
    private boolean flowsSent = false;
    private int measurements = 101;
    private boolean write = false;
    private boolean drillDownEnded = false;
    private ClassifierExecutor classifierExecutor = new ClassifierExecutor();
    ExecutorService executor = Executors.newFixedThreadPool(1);
    private String[] initialSubnets;

    @Override
    public String getName() {
        return ResRuleInstaller.class.getSimpleName();
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
        
                
        boolean serverAvailable = isServerAvailable("localhost", 5555);
        if (serverAvailable) {
            System.out.println("Connected to classifier");
            classifierConnected = true;
        }

        socket.connect("tcp://localhost:5555");

        int maskBits = (int) (Math.log(res) / Math.log(2));
        initialSubnets = IpAddrGenerator.generateIps(maskBits);
        floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        switchService = context.getServiceImpl(IOFSwitchService.class);
        logger = LoggerFactory.getLogger(ResRuleInstaller.class);
        
        logger.info("Rule installer initiated");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown hook triggered. Shutting down executor...");
            // Clean up
            socket.close();
            socketContext.term();
            executor.shutdownNow(); // This interrupts running tasks
            System.out.println("Shutdown complete.");
        }));
        
    }
 
    @Override
    public void startUp(FloodlightModuleContext context) {
        this.floodlightProvider.addOFMessageListener(OFType.BARRIER_REPLY, this);
        this.switchService.addOFSwitchListener(this);
    }
 
    @Override
    public net.floodlightcontroller.core.IListener.Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
        logger.info("Message received of type: {}", msg.getType());
        /*logger.info("Flow sent: {}", flowsSent);

        if (flowsSent) {
            OFBarrierReply barrierReply = (OFBarrierReply) msg;
            long receptionTime = System.nanoTime();
            long instTimeNano = receptionTime - sendingTime;
            double instTimeSeconds = (double)instTimeNano/1000000000;
            logger.info("Barrier reply received after {} seconds", instTimeSeconds);
            logger.info("Reception time: {}", receptionTime);

            DecimalFormat decimalFormat = new DecimalFormat("#,##0.000000");
            String instTimeString = decimalFormat.format(instTimeSeconds);
            String[] resAndInstLat = new String[]{String.valueOf(res), instTimeString};
            String newData = CSVWriter.convertToCSV(resAndInstLat);
            try {
                CSVWriter.writeToCsv("~/floodlight/results/install_latency.csv", newData, write);
            }
            catch (IOException e) {
                logger.info("CSV Writer fn exploded lfmao");
            }
        }*/

        barrierReplies ++;

        return Command.CONTINUE; // Continue with the next listener
    }

    @Override 
    public void switchAdded(DatapathId switchId){
        logger.info("Switch added");
        Runnable flowInstRunnable = new Runnable() {
            @Override
            public void run() {

                // This is for flow installation experiments
                //flowInstallLoop(switchId); 

                //This is for drilldown experiments
                ddStart = System.nanoTime();
                drillDownTest(switchId);

                //Installation and deletion experiments
                //installationAndDeletionTest(switchId);
            }
        };
        Future<?> future = executor.submit(flowInstRunnable); 
    }

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

    private void drillDownTest(DatapathId switchId) {
        String subnetBase = "0.0.0.0";
        int subnetMask = 0;

        int waitTime = 3;
        try {
            TimeUnit.SECONDS.sleep(waitTime); 
        }
        catch(InterruptedException e) { 
            logger.error("Initial wait time interrupted");
        }
        logger.info("Drilldown started");
        
        for (int i = 0; i < measurements; i++) {
            int ddStep = 1;
            ddStart = System.nanoTime();
            while (!drillDownEnded) {
                long ddIterStart = System.nanoTime();
                String subnet = subnetBase + "/" + String.valueOf(subnetMask);
                logger.info("Drilling down into subnet {}", subnet);
                drillDown(subnet, switchId);
                subnetMask += (int) (Math.log(res)/Math.log(2));
                
                //Wait exp time
                try {
                    TimeUnit.MILLISECONDS.sleep(expTimeMillis);
                }
                catch(InterruptedException e) {
                    logger.info("Exposure time interrupted");
                }

                //Get stats
                if (!drillDownEnded) {
                    
                    //TODO maybe do this in another thread
                    ListenableFuture<?> future = sendFlowStatsRequest(switchId);
                    IOFSwitch sw = switchService.getSwitch(switchId);
                    getStats(sw, future);
                    long[][] aggregationMap = createAggregationMap(totalMatchesAndCounts);
                    //printAggMap(aggregationMap);
                            
                    System.out.println("Number of matches and counts: " + totalMatchesAndCounts.size());

                    JSONArray aggMapJSON = new JSONArray();
                    for (long[] row : aggregationMap) {
                        JSONArray rowArray = new JSONArray();
                        for (long value : row) {
                            rowArray.put(value);
                        }
                        aggMapJSON.put(rowArray);
                    }

                    if (classifierConnected) {

                        JSONObject jsonMessage = new JSONObject();

                        // Get the current instant (timestamp)
                        Instant now = Instant.now();

                        // Convert to epoch nanoseconds
                        long unixTimeNano = now.toEpochMilli() * 1_000_000L + now.getNano();

                        try {
                            jsonMessage.put("timestamp", unixTimeNano);
                            jsonMessage.put("aggMap", aggMapJSON);
                        } catch (JSONException e) {
                            System.out.println("Creation of JSON object failed: " + e);
                        }
                        

                        String jsonString = jsonMessage.toString();
                        System.out.println("Sending agg map to classifier");
                        socket.send(jsonString.getBytes(ZMQ.CHARSET), 0);
                        System.out.println("Sending done");
                    }

                    
                    // Wait for the reply from the server
                    Poller poller = socketContext.poller(1); // Poller for 1 socket
                    poller.register(socket, Poller.POLLIN);

                    System.out.println("Waiting for response");
                    // Poll for events
                    poller.poll(5000); // Timeout in milliseconds

                    if (poller.pollin(0)) {
                        // Receive message if available
                
                        String message = socket.recvStr(0);
                        if (message != null) {
                            System.out.println("Received: " + message);
                            long ddIterEnd = System.nanoTime();
                            System.out.println("Iteration start time: " + ddIterStart);
                            System.out.println("Iteration end time: " + ddIterEnd);
                            double ddIterTime = (double) (ddIterEnd - ddIterStart) / 1000000000; 
                            ddIterTime -= (double) expTimeMillis / 1000; //Remove exposure time
                            System.out.println("Drilldown iteration " + ddStep + " took " + ddIterTime + " seconds.");
                            
                            DecimalFormat decimalFormat = new DecimalFormat("#,##0.000000");
                            String ddIterString = decimalFormat.format(ddIterTime);
                            String[] resAndLat = new String[]{String.valueOf(res), ddIterString};
                            String newData = CSVWriter.convertToCSV(resAndLat);
                            try {
                                CSVWriter.writeToCsv("~/floodlight/results/dd_iteration_latency.csv", newData, write);
                            } catch (IOException e) {
                                logger.info("CSV Writer fn exploded lfmao");
                            }

                        }
                    }
                }
                
                ddStep++;
            }
            ddStep --; //Remove last increment as the last iteration only detects whether the drilldown has ended
            
            long ddTimeNano = System.nanoTime() - ddStart;
            double ddTimeSeconds = (double) ddTimeNano/1000000000;
            System.out.println("Drilldown time with exposure time: " + ddTimeSeconds);
            ddTimeSeconds -= ddStep; //Remove exposure time seconds
            DecimalFormat decimalFormat = new DecimalFormat("#,##0.000000");
            String ddTimeString = decimalFormat.format(ddTimeSeconds);
            String[] resAndInstLat = new String[]{String.valueOf(res), ddTimeString};
            String newData = CSVWriter.convertToCSV(resAndInstLat);
            logger.info("Drilldown done in {} seconds", ddTimeString);
            try {
                CSVWriter.writeToCsv("/home/borja/HiWi/floodlight/results/dd_latency_with_classification.csv", newData, write);
            }
            catch (IOException e) {
                logger.info("CSV Writer fn exploded lfmao {}", e);
            }
            
            try {
                TimeUnit.SECONDS.sleep(2); 
            }
            catch(InterruptedException e) { 
                logger.error("Post drilldown wait time interrupted");
            }
            barrierReplies = 0;
            subnetMask = 0;
            drillDownEnded = false;

            try {
                TimeUnit.SECONDS.sleep(2); 
            }
            catch(InterruptedException e) { 
                logger.error("Post drilldown wait time interrupted");
            }
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
        sw.write(flowDelete);
        logger.info("Sent delete all flows command to switch {}", switchId);
        OFBarrierRequest barrierRequest = sw.getOFFactory().buildBarrierRequest().build();
        flowsSent = false; //reset state
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

    private <T> List<List<T>> splitList(List<T> originalList) {
        List<List<T>> sublists = new ArrayList<>();
        int size = originalList.size();
        int chunkSize = (res == 64) ? 4096 : 1024;
        
        for (int i = 0; i < size; i += chunkSize) {
            sublists.add(new ArrayList<>(originalList.subList(i, Math.min(size, i + chunkSize))));
        }
        
        return sublists;
    }

    /*Returns true while the max depth has not been reached
    *@subnet destination (for now) subnet where the drill down occurs
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
            return true;  // Connection successful
        } catch (Exception e) {
            return false;  // Server is not available
        }
    }
}