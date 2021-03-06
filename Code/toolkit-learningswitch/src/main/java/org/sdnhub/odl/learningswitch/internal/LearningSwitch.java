
package org.sdnhub.odl.learningswitch.internal;

import org.sdnhub.odl.learningswitch.ILearningSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

import org.opendaylight.controller.sal.utils.EtherTypes;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.action.Action;
import org.opendaylight.controller.sal.action.Drop;
import org.opendaylight.controller.sal.action.Output;
import org.opendaylight.controller.sal.core.ConstructionException;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.core.NodeConnector;
import org.opendaylight.controller.sal.flowprogrammer.Flow;
import org.opendaylight.controller.sal.flowprogrammer.IFlowProgrammerService;
import org.opendaylight.controller.sal.match.Match;
import org.opendaylight.controller.sal.match.MatchField;
import org.opendaylight.controller.sal.match.MatchType;
import org.opendaylight.controller.sal.packet.BitBufferHelper;
import org.opendaylight.controller.sal.packet.Ethernet;
import org.opendaylight.controller.sal.packet.IDataPacketService;
import org.opendaylight.controller.sal.packet.IListenDataPacket;
import org.opendaylight.controller.sal.packet.IPv4;
import org.opendaylight.controller.sal.packet.TCP;
import org.opendaylight.controller.sal.packet.UDP;
import org.opendaylight.controller.sal.packet.Packet;
import org.opendaylight.controller.sal.packet.PacketResult;
import org.opendaylight.controller.sal.packet.RawPacket;
import org.opendaylight.controller.switchmanager.ISwitchManager;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkUtil;

public class LearningSwitch implements IListenDataPacket, ILearningSwitch {
    private static final Logger logger = LoggerFactory
            .getLogger(LearningSwitch.class);
    private ISwitchManager switchManager = null;
    private IFlowProgrammerService programmer = null;
    private IDataPacketService dataPacketService = null;
    private Map<Long, NodeConnector> mac_to_port = new HashMap<Long, NodeConnector>();
    private String function = "switch";
    
    //Contains all the nodes
    private ArrayList<Node> nodes = new ArrayList<Node>();
    
    //Firewall Attributes
    //Blocked Ports
    private ArrayList<Short> blockedPorts= new ArrayList<Short>();
    //Blocked MACs
    private ArrayList<Long> blockedMACs = new ArrayList<Long>();
    //Blocked IPs
    private ArrayList<InetAddress> blockedIPs = new ArrayList<InetAddress>();
    //Blocked Protocols
    private ArrayList<Byte> blockedProtocols = new ArrayList<Byte>();

    void setDataPacketService(IDataPacketService s) {
        this.dataPacketService = s;
    }

    void unsetDataPacketService(IDataPacketService s) {
        if (this.dataPacketService == s) {
            this.dataPacketService = null;
        }
    }

    public void setFlowProgrammerService(IFlowProgrammerService s)
    {
        this.programmer = s;
    }

    public void unsetFlowProgrammerService(IFlowProgrammerService s) {
        if (this.programmer == s) {
            this.programmer = null;
        }
    }

    void setSwitchManager(ISwitchManager s) {
        logger.debug("SwitchManager set");
        this.switchManager = s;
    }

    void unsetSwitchManager(ISwitchManager s) {
        if (this.switchManager == s) {
            logger.debug("SwitchManager removed!");
            this.switchManager = null;
        }
    }

    /**
     * Function called by the dependency manager when all the required
     * dependencies are satisfied
     *
     */
    void init() {
        logger.info("Initialized Tutorial2Forwarding");
        // Disabling the SimpleForwarding and ARPHandler bundle to not conflict with this one
        BundleContext bundleContext = FrameworkUtil.getBundle(this.getClass()).getBundleContext();
        for(Bundle bundle : bundleContext.getBundles()) {
            if (bundle.getSymbolicName().contains("simpleforwarding")) {
                try {
                    bundle.uninstall();
                } catch (BundleException e) {
                    logger.error("Exception in Bundle uninstall "+bundle.getSymbolicName(), e); 
                }   
            }   
        }
        
        //blockProtocol((byte) 1);
        //blockIP("10.0.0.1");
        //blockMAC("000000000001");
    }

    /**
     * Function called by the dependency manager when at least one
     * dependency become unsatisfied or when the component is shutting
     * down because for example bundle is being stopped.
     *
     */
    void destroy() {
    }

    /**
     * Function called by dependency manager after "init ()" is called
     * and after the services provided by the class are registered in
     * the service registry
     *
     */
    void start() {
        logger.info("Started");
    }

    /**
     * Function called by the dependency manager before the services
     * exported by the component are unregistered, this will be
     * followed by a "destroy ()" calls
     *
     */
    void stop() {
        logger.info("Stopped");
    }

    private void floodPacket(RawPacket inPkt) {
        NodeConnector incoming_connector = inPkt.getIncomingNodeConnector();
        Node incoming_node = incoming_connector.getNode();

        Set<NodeConnector> nodeConnectors =
                this.switchManager.getUpNodeConnectors(incoming_node);

        for (NodeConnector p : nodeConnectors) {
            if (!p.equals(incoming_connector)) {
                try {
                    RawPacket destPkt = new RawPacket(inPkt);
                    destPkt.setOutgoingNodeConnector(p);
                    this.dataPacketService.transmitDataPacket(destPkt);
                } catch (ConstructionException e2) {
                    continue;
                }
            }
        }
    }
    
    
    //Firewall initializer functions interacting with the UI
    public void blockProtocol(byte protocol)
    {
    	if(!blockedProtocols.contains((Byte) protocol))
    	{
	    	blockedProtocols.add(protocol);
	    	resetNodes();
    	}
    }
    
    public void unblockProtocol(byte protocol)
    {
    	blockedProtocols.remove((Byte) protocol);
    	resetNodes();
    }
    
    public void blockPort(short port)
    {
    	if(!blockedPorts.contains((Short) port))
    	{
	    	blockedPorts.add(port);
	    	resetNodes();
    	}
    }
    
    public void unblockPort(short port)
    {
    	blockedPorts.remove((Short) port);
    	resetNodes();
    }
    
    public void blockIP(String ip)
    {
    	try
    	{
	    	InetAddress IP = InetAddress.getByName(ip);
	    	if(!blockedIPs.contains(IP))
	    	{
		    	blockedIPs.add(IP);
		    	resetNodes();
	    	}
    	}
    	catch(Exception e)
    	{
    		e.printStackTrace();
    	}
    }
    
    public void unblockIP(String ip)
    {
    	try
    	{
	    	InetAddress IP = InetAddress.getByName(ip);
	    	blockedIPs.remove(IP);
	    	resetNodes();
    	}
    	catch(Exception e)
    	{
    		e.printStackTrace();
    	}
    }
    
    public void blockMAC(String mac)
    {
    	//Filter out all non-digit characters from MAC address
    	mac.replaceAll(":", "");
    	
    	//Convert the MAC string (base 16) to long
    	long MAC = Long.parseLong(mac, 16);
    	
    	if(!blockedMACs.contains((Long) MAC))
    	{
	    	blockedMACs.add(MAC);
	    	resetNodes();
    	}
    }
    
    public void unblockMAC(String mac)
    {
    	//Filter out all non-digit characters from MAC address
    	mac.replaceAll(":", "");
    	
    	//Convert the MAC string (base 16) to long
    	long MAC = Long.parseLong(mac, 16);
    	
    	blockedMACs.remove((Long) MAC);
    	resetNodes();
    }
    
    //Getters for getting data to display on UI
    public ArrayList<Byte> getBlockedProtocols()
    {
    	return blockedProtocols;
    }
    
    public ArrayList<Short> getBlockedPorts()
    {
    	return blockedPorts;
    }
    
    public ArrayList<InetAddress> getBlockedIPs()
    {
    	return blockedIPs;
    }
    
    public ArrayList<Long> getBlockedMACs()
    {
    	return blockedMACs;
    }
    
    //Remove all flows from the router
    public void resetNodes()
    {
    	if(!nodes.isEmpty())
    	{
	    	for(Node node : nodes)
	    	{
	    		programmer.removeAllFlows(node);
	    	}
    	}
    }



    @Override
    public PacketResult receiveDataPacket(RawPacket inPkt) {
        if (inPkt == null) {
            return PacketResult.IGNORED;
        }

        NodeConnector incoming_connector = inPkt.getIncomingNodeConnector();

        // Hub implementation
        if (function.equals("hub")) {
            floodPacket(inPkt);
        } else {
            Packet linkLayerPacket = this.dataPacketService.decodeDataPacket(inPkt);
            if (!(linkLayerPacket instanceof Ethernet)) {
                return PacketResult.IGNORED;
            }

            //Learn the node
            learnNode(incoming_connector);
            
            //Get the network layer packet encapsulated inside link layer packet as payload
            Packet networkLayerPacket = linkLayerPacket.getPayload();
            
            //Firewall Activities
            //Check if protocol allowed
            if(!checkIfProtocolAllowed(networkLayerPacket))
            {
            	if(programFirewallRejectProtocolFlow(networkLayerPacket, incoming_connector))
	        		return PacketResult.CONSUME;
	        	else
	        		return PacketResult.IGNORED;
            }

            //Get the transport layer packet encapsulated inside network layer packet as payload
            Packet transportLayerPacket = networkLayerPacket.getPayload();
            
            //Check if port allowed
            if(!checkIfPortAllowed(transportLayerPacket))
            {
            	if(programFirewallRejectPortFlow(transportLayerPacket, incoming_connector))
        			return PacketResult.CONSUME;
        		else
        			return PacketResult.IGNORED;
            }
            
            //Check if IP allowed
            if(!checkIfIPAllowed(networkLayerPacket))
            {
            	if(programFirewallRejectIPFlow(networkLayerPacket, incoming_connector))
        			return PacketResult.CONSUME;
        		else
        			return PacketResult.IGNORED;
            }
            //Check if MAC allowed
            if(!checkIfMACAllowed(linkLayerPacket))
            {
            	if(programFirewallRejectMACFlow(linkLayerPacket, incoming_connector))
            		return PacketResult.CONSUME;
            	else
            		return PacketResult.IGNORED;
            }
            
            //If not blocked by the firewall, program that flow
            learnSourceMAC(linkLayerPacket, incoming_connector);
            NodeConnector outgoing_connector = knowDestinationMAC(linkLayerPacket);
            
            if (outgoing_connector == null) 
            {
                floodPacket(inPkt);
            } 
            else 
            {
                if (!programFlow(linkLayerPacket, incoming_connector, outgoing_connector)) 
                {
                    return PacketResult.IGNORED;
                }
                inPkt.setOutgoingNodeConnector(outgoing_connector);
                this.dataPacketService.transmitDataPacket(inPkt);
            }
        }
        return PacketResult.CONSUME;
    }
    
    private void learnNode(NodeConnector incoming_connector)
    {
    	Node node = incoming_connector.getNode();
    	if(!nodes.contains(node))
    	{
    		nodes.add(node);
    	}
    }

    private void learnSourceMAC(Packet formattedPak, NodeConnector incoming_connector) {
        byte[] srcMAC = ((Ethernet)formattedPak).getSourceMACAddress();
        long srcMAC_val = BitBufferHelper.toNumber(srcMAC);
        this.mac_to_port.put(srcMAC_val, incoming_connector);
    }

    private NodeConnector knowDestinationMAC(Packet formattedPak) {
        byte[] dstMAC = ((Ethernet)formattedPak).getDestinationMACAddress();
        long dstMAC_val = BitBufferHelper.toNumber(dstMAC);
        return this.mac_to_port.get(dstMAC_val);
    }
    
    //Block packet if of a blocked protocol
    private boolean checkIfProtocolAllowed(Packet packet) {
    	if(packet instanceof IPv4)
    	{
	    	byte protocol = ((IPv4) packet).getProtocol();
	    	
	    	if(blockedProtocols.contains(protocol))
	    	{
	    		return false;
	    	}
    	}
    	
    	return true;
    }
    
    //Block traffic from or to this MAC address if found in MAC blockedlist
    private boolean checkIfMACAllowed(Packet packet) {
    	byte[] srcMAC = ((Ethernet)packet).getSourceMACAddress();
    	byte[] dstMAC = ((Ethernet)packet).getDestinationMACAddress();
    	
    	long srcMAC_val = BitBufferHelper.toNumber(srcMAC);
        long dstMAC_val = BitBufferHelper.toNumber(dstMAC);
    	
        if((blockedMACs.contains(dstMAC_val)) || (blockedMACs.contains(srcMAC_val)))
        {
        	return false;
        }
        
    	return true;
    }
    
    //Block traffic from or to this Port if found in Port blockedlist
    //Ports can only be checked with TCP and UDP
    private boolean checkIfPortAllowed(Packet packet) {
    	if((packet instanceof TCP) || (packet instanceof UDP))
    	{
    		short srcPort = ((TCP) packet).getSourcePort();
    		short dstPort = ((TCP) packet).getDestinationPort();
    		
    		if((blockedPorts.contains(srcPort)) || (blockedPorts.contains(dstPort)))
    		{
    			return false;
    		}
    	}
    	
    	return true;
    }
    
    //Block traffic from or to this IP address if found in IP blockedlist
    private boolean checkIfIPAllowed(Packet packet) {
    	if(packet instanceof IPv4)
    	{
    		int srcIpAddr = ((IPv4) packet).getSourceAddress();
    		int dstIpAddr = ((IPv4) packet).getDestinationAddress();
    		
    		String src = convertIPtoString(srcIpAddr);
    		String dst = convertIPtoString(dstIpAddr);
    		
    		try
    		{
	    		InetAddress srcIP = InetAddress.getByName(src);
	    		InetAddress dstIP = InetAddress.getByName(dst);
	    		
	    		if(blockedIPs.contains(srcIP))
	    		{
	    			//If source IP is blocked
	    			return false;
	    		}
	    		else if(blockedIPs.contains(dstIP))
	    		{
	    			//If destination IP is blocked
	    			return false;
	    		}
    		}
    		catch(Exception e)
    		{
    			e.printStackTrace();
    		}
    	}
    	
    	return true;
    }
    
    private String convertIPtoString(int ip)
    {
    	String ipStr = 
    	  String.format("%d.%d.%d.%d",
    	         (ip >> 24 & 0xff),   
    	         (ip >> 16 & 0xff),             
    	         (ip >> 8 & 0xff),    
    	         (ip & 0xff));
    	return ipStr;
    }

    private boolean programFlow(Packet formattedPak, 
            NodeConnector incoming_connector, 
            NodeConnector outgoing_connector) {
        byte[] dstMAC = ((Ethernet)formattedPak).getDestinationMACAddress();

        Match match = new Match();
        match.setField( new MatchField(MatchType.IN_PORT, incoming_connector) );
        match.setField( new MatchField(MatchType.DL_DST, dstMAC.clone()) );

        List<Action> actions = new ArrayList<Action>();
        actions.add(new Output(outgoing_connector));

        Flow f = new Flow(match, actions);
        f.setIdleTimeout((short)5);

        // Modify the flow on the network node
        Node incoming_node = incoming_connector.getNode();
        Status status = programmer.addFlow(incoming_node, f);

        if (!status.isSuccess()) {
            logger.warn("SDN Plugin failed to program the flow: {}. The failure is: {}",
                    f, status.getDescription());
            return false;
        } else {
            return true;
        }
    }
    
    private boolean programFirewallRejectProtocolFlow(Packet formattedPak, 
    		NodeConnector incoming_connector) {
    	if(formattedPak instanceof IPv4)
    	{
	        byte protocol = ((IPv4) formattedPak).getProtocol();
	        short ethertype = EtherTypes.IPv4.shortValue();
	
	        Match match = new Match();
	        match.setField( new MatchField(MatchType.DL_TYPE, ethertype));
	        match.setField( new MatchField(MatchType.NW_PROTO, protocol));
	
	        List<Action> actions = new ArrayList<Action>();
	        actions.add(new Drop());
	
	        Flow f = new Flow(match, actions);
	        f.setIdleTimeout((short)5);
	
	        // Modify the flow on the network node
	        Node incoming_node = incoming_connector.getNode();
	        Status status = programmer.addFlow(incoming_node, f);
	
	        if (!status.isSuccess()) {
	            logger.warn("SDN Plugin failed to program the reject protocol flow: {}. The failure is: {}",
	                    f, status.getDescription());
	        } else {
	            return true;
	        }
    	}
    	
        return false;
    }
    
    private boolean programFirewallRejectPortFlow(Packet formattedPak, 
            NodeConnector incoming_connector) {
    	if((formattedPak instanceof TCP) || (formattedPak instanceof UDP))
    	{
    		short srcPort;
    		short dstPort;
    		
    		if(formattedPak instanceof TCP)
    		{
	    		srcPort = ((TCP) formattedPak).getSourcePort();
	    		dstPort = ((TCP) formattedPak).getDestinationPort();
    		}
    		else
    		{
	    		srcPort = ((UDP) formattedPak).getSourcePort();
	    		dstPort = ((UDP) formattedPak).getDestinationPort();
    		}
	
    		short ethertype = EtherTypes.IPv4.shortValue();
    		
	        Match match = new Match();
	        match.setField( new MatchField(MatchType.DL_TYPE, ethertype));
	        
	        if(blockedPorts.contains(srcPort))
	        {
	        	match.setField( new MatchField(MatchType.TP_SRC, srcPort));
	        	match.setField( new MatchField(MatchType.TP_DST, srcPort));
	        }
	        else if(blockedPorts.contains(dstPort))
	        {
	        	match.setField( new MatchField(MatchType.TP_SRC, dstPort));
	        	match.setField( new MatchField(MatchType.TP_DST, dstPort));
	        }
	        else
	        {
	        	return false;
	        }
	
	        List<Action> actions = new ArrayList<Action>();
	        actions.add(new Drop());
	
	        Flow f = new Flow(match, actions);
	        f.setIdleTimeout((short)5);
	
	        // Modify the flow on the network node
	        Node incoming_node = incoming_connector.getNode();
	        Status status = programmer.addFlow(incoming_node, f);
	
	        if (!status.isSuccess()) {
	            logger.warn("SDN Plugin failed to program the reject port flow: {}. The failure is: {}",
	                    f, status.getDescription());
	        } else {
	            return true;
	        }
    	}
        
        return false;
    }
    
    private boolean programFirewallRejectIPFlow(Packet packet, 
    		NodeConnector incoming_connector) {
    	if(packet instanceof IPv4)
    	{
    		int srcIpAddr = ((IPv4) packet).getSourceAddress();
    		int dstIpAddr = ((IPv4) packet).getDestinationAddress();
    		
    		String src = convertIPtoString(srcIpAddr);
    		String dst = convertIPtoString(dstIpAddr);
    		
    		try
    		{
	    		InetAddress srcIP = InetAddress.getByName(src);
	    		InetAddress dstIP = InetAddress.getByName(dst);

	    		short ethertype = EtherTypes.IPv4.shortValue();
	    		
		        Match match = new Match();
		        match.setField( new MatchField(MatchType.DL_TYPE, ethertype));
		        
		        if(blockedIPs.contains(srcIP))
		        {
		        	match.setField( new MatchField(MatchType.NW_SRC, srcIP));
		        	match.setField( new MatchField(MatchType.NW_DST, srcIP));
		        }
		        else if(blockedIPs.contains(dstIP))
		        {
		        	match.setField( new MatchField(MatchType.NW_SRC, dstIP));
		        	match.setField( new MatchField(MatchType.NW_DST, dstIP));
		        }
		        else
		        {
		        	return false;
		        }
		
		        List<Action> actions = new ArrayList<Action>();
		        actions.add(new Drop());
		
		        Flow f = new Flow(match, actions);
		        f.setIdleTimeout((short)5);
		
		        // Modify the flow on the network node
		        Node incoming_node = incoming_connector.getNode();
		        Status status = programmer.addFlow(incoming_node, f);
		
		        if (!status.isSuccess()) {
		            logger.warn("SDN Plugin failed to program the reject IP flow: {}. The failure is: {}",
		                    f, status.getDescription());
		        } else {
		            return true;
		        }
    		}
    		catch(Exception e)
    		{
    			e.printStackTrace();
    		}
    	}
    	
    	return false;
    }
    
    private boolean programFirewallRejectMACFlow(Packet formattedPak, 
    		NodeConnector incoming_connector) {
    	byte[] srcMAC = ((Ethernet)formattedPak).getSourceMACAddress();
        byte[] dstMAC = ((Ethernet)formattedPak).getDestinationMACAddress();

        Match match = new Match();
        if(blockedMACs.contains(srcMAC))
        {
        	match.setField( new MatchField(MatchType.DL_SRC, srcMAC.clone()));
        	match.setField( new MatchField(MatchType.DL_DST, srcMAC.clone()));
        }
        else if(blockedMACs.contains(dstMAC))
        {
        	match.setField( new MatchField(MatchType.DL_SRC, dstMAC.clone()));
        	match.setField( new MatchField(MatchType.DL_DST, dstMAC.clone()));
        }
        else
        {
        	return false;
        }

        List<Action> actions = new ArrayList<Action>();
        actions.add(new Drop());

        Flow f = new Flow(match, actions);
        f.setIdleTimeout((short)5);

        // Modify the flow on the network node
        Node incoming_node = incoming_connector.getNode();
        Status status = programmer.addFlow(incoming_node, f);

        if (!status.isSuccess()) {
            logger.warn("SDN Plugin failed to program the reject MAC flow: {}. The failure is: {}",
                    f, status.getDescription());
            return false;
        } else {
            return true;
        }
    }
}

