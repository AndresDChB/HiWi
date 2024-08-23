package net.floodlightcontroller.packetmonitor;

import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.internal.OFSwitchService;
import net.floodlightcontroller.core.internal.OFSwitchService;
import net.floodlightcontroller.util.MatchUtils;
import org.projectfloodlight.openflow.protocol.OFFlowStatsEntry;
import org.projectfloodlight.openflow.protocol.OFFlowStatsReply;
import org.projectfloodlight.openflow.protocol.OFFlowStatsRequest;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFStatsReply;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.OFGroup;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

public class PacketMonitor {
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> monitoringTask;
    private int expTime = 2;


    @Override 
    public void switchAdded(DatapathId switchId) {
        // Schedule the monitoring task to run periodically
        monitoringTask = scheduler.scheduleAtFixedRate(
            () -> startMonitoring(switchId),
            0, // Initial delay
            expTime, // Period between executions
            TimeUnit.SECONDS
        );
    }

    private void startMonitoring(DatapathId switchId) {
        IOFSwitch sw = switchService.getSwitch(switchId);
        ListenableFuture<?> future;
        List<OFStatsReply> values = null;
        ArrayList<Float> durations = new ArrayList<>();

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
            logger.info("Values received");

            for (OFStatsReply reply : values) {
                if (reply instanceof OFFlowStatsReply) {
                    OFFlowStatsReply flowStatsReply = (OFFlowStatsReply) reply;
                    
                    for (OFFlowStatsEntry entry : flowStatsReply.getEntries()) {
                        long durationSeconds = entry.getDurationSec();
                        long durationNanoseconds = entry.getDurationNsec();

                        float totalDuration = durationSeconds + ((float)durationNanoseconds / 1_000_000_000);
                        durations.add(totalDuration);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failure retrieving statistics from switch {}. {}", sw, e);
        }

        if (durations.isEmpty()) {
            logger.info("No durations could be read");
        } else {
            float max = Collections.max(durations);
            float min = Collections.min(durations);
            float instTime = max - min; 
            logger.info("Installation time: {} seconds", instTime);
        }
    }

    @Override
    public void shutdown() {
        // Stop the monitoring task gracefully when shutting down Floodlight
        if (monitoringTask != null && !monitoringTask.isCancelled()) {
            monitoringTask.cancel(true); // Cancel the task
            scheduler.shutdown(); // Shutdown the scheduler
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow(); // Force shutdown if it takes too long
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow(); // Force shutdown if interrupted
            }
        }
        logger.info("Floodlight shutdown completed.");
    }
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        return null;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        return null;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        Collection<Class<? extends IFloodlightService>> l = new ArrayList<>();
        l.add(IFloodlightProviderService.class);
        l.add(IOFSwitchService.class);
        return l;
    }

    @Override
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        switchService = context.getServiceImpl(IOFSwitchService.class);
        scheduler = Executors.newScheduledThreadPool(1);
        logger.info("PacketMonitor module initialized.");
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
        switchService.addOFSwitchListener(this);
        logger.info("PacketMonitor module started.");
    }

    @Override
    public void switchRemoved(DatapathId switchId) {
        logger.info("Switch {} removed.", switchId);
    }

    @Override
    public void switchActivated(DatapathId switchId) {
        logger.info("Switch {} activated.", switchId);
    }

    @Override
    public void switchPortChanged(DatapathId switchId, OFPortDesc port, PortChangeType type) {
        // Handle port changes if needed
    }

    @Override
    public void switchChanged(DatapathId switchId) {
        // Handle switch changes if needed
    }

    @Override
    public void switchDeactivated(DatapathId switchId) {
        logger.info("Switch {} deactivated.", switchId);
    }
}