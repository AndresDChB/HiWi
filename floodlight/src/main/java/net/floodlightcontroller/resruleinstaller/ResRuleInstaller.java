package net.floodlightcontroller.resruleinstaller;
 
import java.util.Collections;
import java.util.Collection;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.*;
import java.util.concurrent.*;
import java.text.DecimalFormat;
import java.io.IOException;
 
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import net.floodlightcontroller.core.PortChangeType;

import csvwriter.CSVWriter;
 
public class ResRuleInstaller implements IOFMessageListener, IFloodlightModule, IOFSwitchListener {
    
    protected IFloodlightProviderService floodlightProvider;
    protected static Logger logger;
    private IOFSwitchService switchService;
    private int res = 4;
    //For drilldown experiments
    private int expectedBarrierReplies = (int) 32 / (int) (Math.log(res) / Math.log(2)) * 2;
    private int barrierReplies = 0;
    private long sendingTime = 0;
    private long ddStart = 0;
    private boolean flowsSent = false;
    private int measurements = 101;
    private boolean write = true;
    private boolean drillDownEnded = false;
    ExecutorService executor = Executors.newFixedThreadPool(1);
    private String[] initialSubnets;

    @Override
    public String getName() {
        return ResRuleInstaller.class.getSimpleName();
    }
 
    @Override
    public boolean isCallbackOrderingPrereq(OFType type, String name) {
        // TODO Auto-generated method stub
        return false;
    }
 
    @Override
    public boolean isCallbackOrderingPostreq(OFType type, String name) {
        // TODO Auto-generated method stub
        return false;
    }
 
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        // TODO Auto-generated method stub
        return null;
    }
 
    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        // TODO Auto-generated method stub
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
        int maskBits = (int) (Math.log(res) / Math.log(2));
        initialSubnets = IpAddrGenerator.generateIps(maskBits);
        floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        switchService = context.getServiceImpl(IOFSwitchService.class);
        logger = LoggerFactory.getLogger(ResRuleInstaller.class);
        
        logger.info("Rule installer initiated");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown hook triggered. Shutting down executor...");
            executor.shutdownNow(); // This interrupts running tasks
            Thread.currentThread().interrupt();
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
        if ( barrierReplies >= expectedBarrierReplies) {
            long ddTimeNano = System.nanoTime() - ddStart;
            double ddTimeSeconds = (double) ddTimeNano/1000000000;
            DecimalFormat decimalFormat = new DecimalFormat("#,##0.000000");
            String ddTimeString = decimalFormat.format(ddTimeSeconds);
            String[] resAndInstLat = new String[]{String.valueOf(res), ddTimeString};
            String newData = CSVWriter.convertToCSV(resAndInstLat);
            logger.info("Drilldown done in {} seconds", ddTimeString);
            try {
                CSVWriter.writeToCsv("/home/borja/HiWi/floodlight/results/dd_latency.csv", newData, write);
            }
            catch (IOException e) {
                logger.info("CSV Writer fn exploded lfmao {}", e);
            }
        }

        return Command.CONTINUE; // Continue with the next listener
    }

    @Override 
    public void switchAdded(DatapathId switchId){
        logger.info("Switch added");
        Runnable flowInstRunnable = new Runnable() {
            @Override
            public void run() {
                //flowInstallLoop(switchId);
                ddStart = System.nanoTime();
                drillDownTest(switchId);
            }
        };
        Future<?> future = executor.submit(flowInstRunnable); 
    }

    private void drillDownTest(DatapathId switchId) {
        String subnetBase = "0.0.0.0";
        int subnetMask = 0;


        int waitTime = 3;
        try {
            TimeUnit.SECONDS.sleep(waitTime); 
        }
        catch(InterruptedException e) { 
            logger.error("Initial wait time failed");
        }
        logger.info("Drilldown started");
        
        
        for (int i = 0; i < measurements; i++) {
            ddStart = System.nanoTime();
            while (!drillDownEnded) {
                String subnet = subnetBase + "/" + String.valueOf(subnetMask);
                logger.info("Drilling down into subnet {}", subnet);
                drillDown(subnet, switchId);
                subnetMask += (int) (Math.log(res)/Math.log(2));
            }
            try {
                TimeUnit.SECONDS.sleep(2); 
            }
            catch(InterruptedException e) { 
                logger.error("Post drilldown wait time failed");
            }
            barrierReplies = 0;
            subnetMask = 0;
            drillDownEnded = false;

            try {
                TimeUnit.SECONDS.sleep(2); 
            }
            catch(InterruptedException e) { 
                logger.error("Post drilldown wait time failed");
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

        for (List<OFMessage> batch : batches) {
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
        int chunkSize = (res == 64) ? 1 : 512;
        
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
        //TODO remove this
        for (String subnetString : drillDownSubnets) {
            System.out.println(subnetString);
        }
        for (String subnetString : initialSubnets) {
            System.out.println(subnetString);
        }
        List<OFMessage> drillDownFlows = createMatches(initialSubnets, drillDownSubnets, sw);
        logger.info("Drilldown rules created");
        installFlows(switchId, drillDownFlows);
        logger.info("Flows sent");
        
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

 
}