package net.floodlightcontroller.packetmonitor;
 
import java.util.Collections;
import java.util.Collection;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
 
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFType;

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
import org.projectfloodlight.openflow.types.*;

import net.floodlightcontroller.core.IFloodlightProviderService;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.Set;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import net.floodlightcontroller.packet.Ethernet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import net.floodlightcontroller.core.PortChangeType;

public class PacketMonitor implements IOFMessageListener, IFloodlightModule, IOFSwitchListener {
    protected IFloodlightProviderService floodlightProvider;
    protected static Logger logger;
    private IOFSwitchService switchService;
    private ScheduledExecutorService scheduler;
    private int expTime = 1;
    private ScheduledFuture<?> monitoringTask;


    @Override
    public String getName() {
        return PacketMonitor.class.getSimpleName();
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

        floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        switchService = context.getServiceImpl(IOFSwitchService.class);
        logger = LoggerFactory.getLogger(PacketMonitor.class);
        scheduler = Executors.newScheduledThreadPool(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownScheduler();
            logger.info("Scheduler shutdown completed.");
        }));
        logger.info("Packet monitor initiated");
        
    }
 
    @Override
    public void startUp(FloodlightModuleContext context) {
        this.floodlightProvider.addOFMessageListener(OFType.STATS_REPLY, this);
        this.switchService.addOFSwitchListener(this);
    }
 
    @Override
    public net.floodlightcontroller.core.IListener.Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
        return null;
    }

    @Override 
    public void switchAdded(DatapathId switchId){

    }


    private void sendFlowStatsRequest(DatapathId switchId) {
        IOFSwitch sw = switchService.getSwitch(switchId);
        ListenableFuture<?> future;
        List<OFStatsReply> values = null;
        ArrayList<Integer> packets = new ArrayList<Integer>();

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
        
        try {
            values = (List<OFStatsReply>) future.get(5, TimeUnit.SECONDS);
            logger.info("Stats received");

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
                        IPv4Address srcIp = currentMatch.get(MatchField.IPV4_SRC);
                        IPv4Address dstIp = currentMatch.get(MatchField.IPV4_DST);
                        
                        
                        //TODO create list of tuple with IPSrc, IPDst, and Packet count per match. Add javatuples in maven for this
                        U64 packetCount = entry.getPacketCount();
                    }
                }
            }
        }
        catch (Exception e){
            logger.error("Failure retrieving statistics from switch {}. {}", sw, e);
        }
    }

    @Override
    public void switchRemoved(DatapathId switchId){
        logger.info("Switch removed");
    }   

    @Override
    public void switchActivated(DatapathId switchId){
        logger.info("Swicth activated");
        /*monitoringTask = scheduler.scheduleAtFixedRate(
            () -> sendFlowStatsRequest(switchId),
            expTime, // Initial delay
            expTime, // Period between executions
            TimeUnit.SECONDS
        );*/
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
    private void shutdownScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow(); // Force shutdown if tasks don't finish in time
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow(); // Force shutdown if interrupted
            }
        }
    }
}
