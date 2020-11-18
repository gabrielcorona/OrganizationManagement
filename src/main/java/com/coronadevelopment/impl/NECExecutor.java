package com.mirrorlife.cube.core.gateway.nec;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeoutException;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;

import com.mirrorlife.cube.services.ServiceException;

import org.llrp.ltk.exceptions.InvalidLLRPMessageException;
import org.llrp.ltk.generated.enumerations.*;
import org.llrp.ltk.generated.interfaces.*;
import org.llrp.ltk.generated.messages.*;
import org.llrp.ltk.generated.parameters.*;
import org.llrp.ltk.net.*;
import org.llrp.ltk.types.*;
import org.llrp.ltk.util.Util;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class NECExecutor implements LLRPEndpoint {
	public NECGateway server;
	private LLRPConnection connection;    
    private String[] activeAntennas;
    private ROSpec rospec;
    private Integer MessageID = 230; // a random starting point

    private BlockingQueue<Object> msgQueue = new LinkedBlockingDeque<>();

	public NECExecutor(NECGateway server) {
        this.server = server;
    }

	private UnsignedInteger getUniqueMessageID() {
	    return new UnsignedInteger(MessageID++);
	}
	
	public void connect(String ip) {
	    // create client-initiated LLRP connection	
	    connection = new LLRPConnector(this, ip);
	
	    // connect to reader
	    // LLRPConnector.connect waits for successful
	    // READER_EVENT_NOTIFICATION from reader
	    try {
	        log.info("Initiate LLRP connection to NEC Reader");
	        ((LLRPConnector) connection).connect();
	    } catch (LLRPConnectionAttemptFailedException e) {	        
	        log.error("LLRP connection Attempt failed for NEC Reader", e);
	    }
	}
	
	private void disconnect() {
	    LLRPMessage response;
	    CLOSE_CONNECTION close = new CLOSE_CONNECTION();
	    close.setMessageID(getUniqueMessageID());
	    try {
	        // don't wait around too long for close
	        response = connection.transact(close, 4000);
	
	        // check whether ROSpec addition was successful
	        StatusCode status = ((CLOSE_CONNECTION_RESPONSE)response).getLLRPStatus().getStatusCode();
	        if (status.equals(new StatusCode("M_Success"))) {
	            log.info("CLOSE_CONNECTION was successful");
	        }
	        else {
	            log.info(response.toXMLString());
	            log.error("CLOSE_CONNECTION Failed ... continuing anyway");
	        }
	    } catch (InvalidLLRPMessageException e) {
	        log.error("CLOSE_CONNECTION: Received invalid response message", e);
	    } catch (TimeoutException e) {
	        log.error("CLOSE_CONNECTION Timeouts ... continuing anyway", e);
	    } finally {
			((LLRPConnector)connection).disconnect();
		}
	}
	
	public void setConfiguration() {
	    LLRPMessage response;
	
	    try {
	        // Setting up NEC Reader Configuration.
	        log.info("SET_READER_CONFIG with provided values ...");
	        SET_READER_CONFIG set = new SET_READER_CONFIG();
	        set.setMessageID(getUniqueMessageID());
	        ROReportSpec roReportSpec = new ROReportSpec();
			roReportSpec.setN(new UnsignedShort(0));
			roReportSpec.setROReportTrigger(new ROReportTriggerType(
					ROReportTriggerType.Upon_N_Tags_Or_End_Of_ROSpec));
			TagReportContentSelector tagReportContentSelector = new TagReportContentSelector();
			tagReportContentSelector.setEnableAccessSpecID(new Bit(0));
			tagReportContentSelector.setEnableChannelIndex(new Bit(1));
			tagReportContentSelector.setEnableFirstSeenTimestamp(new Bit(0));
			tagReportContentSelector.setEnableInventoryParameterSpecID(new Bit(0));
			tagReportContentSelector.setEnableLastSeenTimestamp(new Bit(0));
			tagReportContentSelector.setEnablePeakRSSI(new Bit(0));
			tagReportContentSelector.setEnableSpecIndex(new Bit(0));
			tagReportContentSelector.setEnableTagSeenCount(new Bit(0));
			
			tagReportContentSelector.setEnableAntennaID(new Bit(1));
			tagReportContentSelector.setEnableROSpecID(new Bit(1));
			
			C1G2EPCMemorySelector epcMemSel = new C1G2EPCMemorySelector();
			epcMemSel.setEnableCRC(new Bit(0));
			epcMemSel.setEnablePCBits(new Bit(0));
			tagReportContentSelector
					.addToAirProtocolEPCMemorySelectorList(epcMemSel);
			roReportSpec.setTagReportContentSelector(tagReportContentSelector);
			set.setROReportSpec(roReportSpec);
	        set.setResetToFactoryDefault(new Bit(false));
	        response =  connection.transact(set, 10000);
	
	        // check whether ROSpec addition was successful
	        StatusCode status = ((SET_READER_CONFIG_RESPONSE)response).getLLRPStatus().getStatusCode();
	        if (status.equals(new StatusCode("M_Success"))) {
	                log.info("NEC SET_READER_CONFIG was successful");
	        }
	        else {
	                log.error("NEC SET_READER_CONFIG Failure");
	        }
	
	    } catch (Exception e) {
	        e.printStackTrace();
	        System.exit(1);
	    }
	}

	private ADD_ROSPEC buildROSpec() {
	    log.info("Building ADD_ROSPEC message from scratch ...");
	    ADD_ROSPEC addRoSpec = new ADD_ROSPEC();
	    addRoSpec.setMessageID(getUniqueMessageID());
	
	    rospec = new ROSpec();
	
	    // Setup the basic info for the RO Spec.
	    rospec.setCurrentState(new ROSpecState(ROSpecState.Disabled));
	    rospec.setPriority(new UnsignedByte(0));
	    rospec.setROSpecID(new UnsignedInteger(123));
	
	    // Set the start and stop conditions for the ROSpec.
	    // For now, we will start and stop manually 
	    ROBoundarySpec boundary = new ROBoundarySpec();
	    ROSpecStartTrigger start = new ROSpecStartTrigger();
	    ROSpecStopTrigger stop = new ROSpecStopTrigger();
	    start.setROSpecStartTriggerType(new ROSpecStartTriggerType(ROSpecStartTriggerType.Null));
	    stop.setROSpecStopTriggerType(new ROSpecStopTriggerType(ROSpecStopTriggerType.Null));
	    stop.setDurationTriggerValue(new UnsignedInteger(0));
	    boundary.setROSpecStartTrigger(start);
	    boundary.setROSpecStopTrigger(stop);
	    rospec.setROBoundarySpec(boundary);
	
	    // Setup what we want to do in the ROSpec.
	    AISpec aispec = new AISpec();
	
	    // what antennas to use.
	    UnsignedShortArray ants = new UnsignedShortArray();
	    for(String activeAntenna : getActiveAntennas()) {
	    	// 0 means all antennas according to documentation, but this seems to don't be the case.
	    	ants.add(new UnsignedShort(Integer.parseInt(activeAntenna)));
	    }
	    aispec.setAntennaIDs(ants);
	    
	    // Setup the AISpec stop condition and options for inventory
	    AISpecStopTrigger aistop = new AISpecStopTrigger();
	    aistop.setAISpecStopTriggerType(new AISpecStopTriggerType(AISpecStopTriggerType.Null));
	    aistop.setDurationTrigger(new UnsignedInteger(0));
	    aispec.setAISpecStopTrigger(aistop);
	
	    // Setup any override configuration.  none in this case
	    InventoryParameterSpec ispec = new InventoryParameterSpec();
	    ispec.setAntennaConfigurationList(null);
	    ispec.setInventoryParameterSpecID(new UnsignedShort(23));
	    ispec.setProtocolID(new AirProtocols(AirProtocols.EPCGlobalClass1Gen2));
	    List<InventoryParameterSpec> ilist = new ArrayList<InventoryParameterSpec>();
	    ilist.add(ispec);
	
	    aispec.setInventoryParameterSpecList(ilist);
	    List<SpecParameter> slist = new ArrayList<SpecParameter>();
	    slist.add(aispec);
	    rospec.setSpecParameterList(slist);
	    
	    addRoSpec.setROSpec(rospec);
	    
	    return addRoSpec;
	}
	
	public void addRoSpec() {
	    LLRPMessage response;
	
	    ADD_ROSPEC addRospec = null;
        addRospec = buildROSpec();
	
	    addRospec.setMessageID(getUniqueMessageID());
	    rospec = addRospec.getROSpec();
	    try {
	        response =  connection.transact(addRospec, 10000);
	
	        // check whether ROSpec addition was successful
	        StatusCode status = ((ADD_ROSPEC_RESPONSE)response).getLLRPStatus().getStatusCode();
	        if (status.equals(new StatusCode("M_Success"))) {
	                log.info("ADD_ROSPEC was successful");
	        }
	        else {
	                log.error("ADD_ROSPEC failures");
	        }
	    } catch (TimeoutException e) {
	        log.error("Timeout waiting for ADD_ROSPEC response",e);
	    }
	}
	
	private void enable() {
	    LLRPMessage response;
	    try {
	        // factory default the reader
	        log.info("ENABLE_ROSPEC ...");
	        ENABLE_ROSPEC ena = new ENABLE_ROSPEC();
	        ena.setMessageID(getUniqueMessageID());
	        ena.setROSpecID(rospec.getROSpecID());
	
	        response =  connection.transact(ena, 10000);
	
	        // check whether ROSpec addition was successful
	        StatusCode status = ((ENABLE_ROSPEC_RESPONSE)response).getLLRPStatus().getStatusCode();
	        if (status.equals(new StatusCode("M_Success"))) {
	                log.info("ENABLE_ROSPEC was successful");
	        }
	        else {
	                log.error(response.toXMLString());
	                log.info("ENABLE_ROSPEC_RESPONSE failed ");
	                System.exit(1);
	        }
	    } catch (Exception e) {
	        e.printStackTrace();
	        System.exit(1);
	    }
	}
	
	public void startInventory() {
	    LLRPMessage response;
	    try {
	        log.info("START_ROSPEC ...");
	        START_ROSPEC start = new START_ROSPEC();
	        start.setMessageID(getUniqueMessageID());
	        start.setROSpecID(rospec.getROSpecID());
	
	        response =  connection.transact(start, 10000);
	
	        // check whether ROSpec addition was successful
	        StatusCode status = ((START_ROSPEC_RESPONSE)response).getLLRPStatus().getStatusCode();
	        if (status.equals(new StatusCode("M_Success"))) {
	                log.info("START_ROSPEC was successful");
	        }
	        else {
	                log.error(response.toXMLString());
	                log.info("START_ROSPEC_RESPONSE failed ");
	                System.exit(1);
	        }
	    } catch (Exception e) {
	        e.printStackTrace();
	        System.exit(1);
	    }
	}
	
	public void stopInventory() {
	    LLRPMessage response;
	    try {
	        log.info("STOP_ROSPEC ...");
	        STOP_ROSPEC stop = new STOP_ROSPEC();
	        stop.setMessageID(getUniqueMessageID());
	        stop.setROSpecID(rospec.getROSpecID());
	
	        response =  connection.transact(stop, 10000);
	
	        // check whether ROSpec addition was successful
	        StatusCode status = ((STOP_ROSPEC_RESPONSE)response).getLLRPStatus().getStatusCode();
	        if (status.equals(new StatusCode("M_Success"))) {
	                log.info("STOP_ROSPEC was successful");
	        }
	        else {
	                log.error(response.toXMLString());
	                log.info("STOP_ROSPEC_RESPONSE failed ");
	                System.exit(1);
	        }
	    } catch (Exception e) {
	        e.printStackTrace();
	        System.exit(1);
	    }
	}
	
	private void logOneTagReport(TagReportData tr) {
	    LLRPParameter epcp = (LLRPParameter) tr.getEPCParameter();
	
	    // epc is not optional, so we should fail if we can't find it
	    String epcString = "EPC:";
	    if(epcp != null) {
	        if( epcp.getName().equals("EPC_96")) {
	            EPC_96 epc96 = (EPC_96) epcp;
	            epcString += epc96.getEPC().toString();
	        } else if ( epcp.getName().equals("EPCData")) {
	            EPCData epcData = (EPCData) epcp;
	            epcString += epcData.getEPC().toString();
	        }
	    } else {
	        log.error("Could not find EPC in Tag Report");
	    }
	
	    // all of these values are optional, so check their non-nullness first
	    if(tr.getAntennaID() != null) {
	        epcString += ",Antenna:" +
	                tr.getAntennaID().getAntennaID().toString();
	    }
	
	    if(tr.getChannelIndex() != null) {
	        epcString += ",ChanIndex:" +
	                tr.getChannelIndex().getChannelIndex().toString();
	    }
	
	    if( tr.getFirstSeenTimestampUTC() != null) {
	        epcString += ",FirstSeen:" +
	                tr.getFirstSeenTimestampUTC().getMicroseconds().toString();
	    }
	
	    if(tr.getInventoryParameterSpecID() != null) {
	        epcString += ",ParamSpecID:" +
	                tr.getInventoryParameterSpecID().getInventoryParameterSpecID().toString();
	    }
	
	    if(tr.getLastSeenTimestampUTC() != null) {
	        epcString += ",LastTime:" +
	                tr.getLastSeenTimestampUTC().getMicroseconds().toString();
	    }
	
	    if(tr.getPeakRSSI() != null) {
	        epcString += ",RSSI:" +
	                tr.getPeakRSSI().getPeakRSSI().toString();
	    }
	
	    if(tr.getROSpecID() != null) {
	        epcString += ",ROSpecID:" +
	                tr.getROSpecID().getROSpecID().toString();
	    }
	
	    if(tr.getTagSeenCount() != null) {
	        epcString += ",SeenCount:" +
	                tr.getTagSeenCount().getTagCount().toString();
	    }
	
	    log.debug(epcString);
	    msgQueue.add(epcString);
	}
	
	// messageReceived method is called whenever a message is received
	// asynchronously on the LLRP connection.
	public void messageReceived(LLRPMessage message) {
	    // convert all messages received to LTK-XML representation
	    // and print them to the console
	
	    log.debug("NEC Reader: Received " + message.getName() + " message asychronously");
	
	    if (message.getTypeNum() == RO_ACCESS_REPORT.TYPENUM) {
	        RO_ACCESS_REPORT report = (RO_ACCESS_REPORT) message;
	        try {
				log.debug("NEC Reader: Trying..."+report.toBinaryString());
			} catch (InvalidLLRPMessageException e) {
				log.error("NEC Reader: Invalid LLRP Message",e);
			}
	        List<TagReportData> tdlist = report.getTagReportDataList();
	
	        for (TagReportData tr : tdlist) {
	            logOneTagReport(tr);
	        }
	    } else if (message.getTypeNum() == READER_EVENT_NOTIFICATION.TYPENUM) {
	        log.debug("NEC Reader: This doesn't look lika a report");
	    }
	}

	public void errorOccured(String s) {
	    log.error(s);
	}
	
	public String[] getActiveAntennas() {
		return activeAntennas;
	}

	public void setActiveAntennas(String[] activeAntennas) {
		this.activeAntennas = activeAntennas;
	}
	
	public BlockingQueue<Object> getMsgQueue() {
		return msgQueue;
	}
    
	public void startExecutor() {
		this.startInventory();
    }
	
	public void stopExecutor() {
        this.stopInventory();
        this.disconnect();
        msgQueue.clear();
    }
    
}