package net.floodlightcontroller.resruleinstaller;

// Import for Floodlight services and modules
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IListener.Command;
import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.PortChangeType;

// Imports for OpenFlow
import org.projectfloodlight.openflow.protocol.OFBarrierRequest;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFStatsRequest;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.types.*;


// Imports for Java collections and utilities
import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Logging
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Import for IOFSwitchService
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.IFloodlightService;

public class ResRuleInstaller implements IOFMessageListener, IFloodlightModule, IOFSwitchListener {
    
    protected IFloodlightProviderService floodlightProvider;
    protected static Logger logger;
    private IOFSwitchService switchService;
    private int res;


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

        floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        switchService = context.getServiceImpl(IOFSwitchService.class);
        logger = LoggerFactory.getLogger(ResRuleInstaller.class);
        res = 4;
        logger.info("Rule installer initiated");
        
    }
 
    @Override
    public void startUp(FloodlightModuleContext context) {
        this.floodlightProvider.addOFMessageListener(OFType.BARRIER_REPLY, this);
        this.switchService.addOFSwitchListener(this);
    }
 
    @Override
    public net.floodlightcontroller.core.IListener.Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
        logger.info("Message received of type {}",msg.getType());
        return Command.CONTINUE; // Continue with the next listener
    }

    @Override 
    public void switchAdded(DatapathId switchId){
        logger.info("Switch added");
        installFlows(switchId, res);
    }
    
    private List<OFMessage> createMatches(String[] IPs, IOFSwitch sw) {

        List<OFMessage> flowMods = new ArrayList<>();
        OFFactory ofFactory = sw.getOFFactory();
        
        //TODO check valid res
        int maskBits = (int)(Math.log(res)/Math.log(2));

        for (String IPSrc : IPs) {
            for (String IPDst : IPs){
                OFFlowAdd newFlow = ofFactory.buildFlowAdd()
                    .setMatch(ofFactory.buildMatch()
                            .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                            .setMasked(MatchField.IPV4_SRC, IPv4AddressWithMask.of(IPSrc + "/" + maskBits))
                            .setMasked(MatchField.IPV4_DST, IPv4AddressWithMask.of(IPDst + "/" + maskBits))
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
    public void installFlows(DatapathId switchId,  int res){
        
        IOFSwitch sw = switchService.getSwitch(switchId);
        
        if (sw == null) {
            throw new RuntimeException("Switch " + switchId + " not found!");
        }

        String[] IPs = IpAddrGenerator.generateIps(res);
        List<OFMessage> flowMods = createMatches(IPs, sw);

        sw.write(flowMods);
        logger.info("Flow rules sent");
        OFBarrierRequest barrierRequest = sw.getOFFactory().buildBarrierRequest().build();
        sw.write(barrierRequest);

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

