package com.mirrorlife.cube.core.gateway.nec;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

import com.mirrorlife.cube.core.notify.XmppNotify;
import com.mirrorlife.cube.util.Constants;
import org.apache.commons.lang3.StringUtils;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.service.IoConnector;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.keepalive.KeepAliveFilter;
import org.apache.mina.filter.keepalive.KeepAliveRequestTimeoutHandler;
import org.apache.mina.filter.logging.LoggingFilter;
import org.apache.mina.transport.socket.DatagramSessionConfig;
import org.apache.mina.transport.socket.nio.NioDatagramAcceptor;
import org.apache.mina.transport.socket.nio.NioDatagramConnector;
import org.apache.mina.transport.socket.nio.NioSocketConnector;
import com.mirrorlife.cube.core.gateway.AbstractGateway;
import com.mirrorlife.cube.core.gateway.udpip.UdpServerHandler;
import com.mirrorlife.cube.core.unit.Unit;
import com.mirrorlife.cube.model.dao.dictionary.SGatewayForward;
import com.mirrorlife.cube.model.dao.master.MGateway;

import lombok.extern.log4j.Log4j2;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.WriteFuture;

@Log4j2
public class NECGateway extends AbstractGateway {
    public static final String CMD_START = ""; // ASC 02H
    public static final String CMD_END = ""; // ASC 03H

    public final static int CMD_REQUEST_TIMEOUT = 5000;//5sec
    public final static boolean KEEP_ALIVE_FLAG = true;
    public static final String KEEP_ALIVE_CMD = "36";
    public final static int KEEP_ALIVE_REQUEST_INTERVAL = 60;
    public final static int KEEP_ALIVE_REQUEST_TIMEOUT = 5;

    Map<String, Unit> unitsKeyIndex = new ConcurrentHashMap<>();
    Map<String, Object> attachKeyIndex = new ConcurrentHashMap<>();

    private NECExecutor necExecutor;
    private NECHandler necHandler;
    private MonitorThd monitor;

    public NECGateway(MGateway gatewayInfo, List<SGatewayForward> listForward, List<Unit> listUnits) {
        super(gatewayInfo, listForward, listUnits);
    }

    @Override
    public void connect() throws Exception {
    	
    	necExecutor = new NECExecutor(this);
    	necExecutor.connect(getIp());
        sendInitConfig();
        necHandler = new NECHandler(necExecutor);
    }

    private void sendInitConfig() {
    	necExecutor.setConfiguration();
    	try {
    		necExecutor.setActiveAntennas(getAntenas());
    	} catch(Exception e) {
    		log.error("Error trying to set antennas.", e);
    	}
    	necExecutor.addRoSpec();
    }

    @Override
    public void messageReceived(Object arg) {
        String msg = arg.toString();

        if (msg.length() > 12) {
            try {
                log.info("NEC messageReceived. IP:" + this.getIp() + "," + msg);
            } catch (Exception e) {
            }
        }

        boolean found = false;
        String addr = "";

        for (SGatewayForward f : this.forwarderList) {
            Matcher m = f.getForwarder().matcher(msg);
            if (m.find()) {
                found = true;
                if (!this.isIntervalPassed(f)) {
                    return;
                }
                int groups = m.groupCount();
                if (groups > 0) {
                    for (int i = 1; i <= groups; i++) {
                        addr = addr + m.group(i).trim();
                    }
                }
                break;
            }
        }

        // fixme for performance
//	        saveGatewayLog(null, msg);

        Unit unit = null;
        if (!StringUtils.isEmpty(addr)) {
            unit = this.getUnitWithAddr(addr);
        } else if (found) {
            // fixme
            // agency unit msg
        }

        if (unit != null) {
            unit.messageReceived(msg, null);
        } else {
            log.warn("Failed to locate a unit or Forwarder undefined: " + msg);
        }
    }

    @Override
    public void sendCmd(Unit unit, Object arg) throws Exception {
        Object cmd = unit.getUnitInfo().getOutput();
        Thread.sleep(500);

        this.unitsKeyIndex.put(cmd.toString(), unit);
        this.attachKeyIndex.put(cmd.toString(), arg);

        if (cmd != null) {
           
//           this.necnecExecutor.sendCmd(cmd);
           
        } else {
            log.warn("Failed to execute command: " + cmd);
        }

    }

    private Object getOrgAtomCmd(String msg) {
        String index = msg;
        Object atom = this.attachKeyIndex.get(index);
        if (atom != null) {
            this.attachKeyIndex.remove(index);
        }
        return atom;
    }

    private String getIp() throws Exception {
        return this.getGatewayInfo().getJsonConfMap().get("NEC_IP").getValue().toString();
    }

    private int getPort() throws Exception {
        return Integer.valueOf(getGatewayInfo().getJsonConfMap().get("NEC_PORT").getValue().toString());
    }

    private String[] getAntenas() throws Exception {
    	return this.getGatewayInfo().getJsonConfMap().get("NEC_ANTENNA_PORT_LIST").getValue().toString().split(",");
    }
    /**
     * Reconnect every 1 minute if disconnected
     */
    class MonitorThd extends Thread {
        private boolean isStop;

        public MonitorThd() {
        	
        }

        @Override
        public void run() {
            while (!isStop()) {
                try {
//	                    if (!sessionU.isConnected() || !sessionT.isConnected() || !sessionU.isActive() || !sessionT.isActive()) {
                    if (System.currentTimeMillis() - NECHandler.lastUpdate > 1 * 60 * 1000) {
                        log.info("CSLGateway reconnecting : " + getIp());

                        /** 再接続 */
                        connect();
                    }
                } catch (Exception e) {
                    log.error("Reconnect failed.", e);
                }
                try {
                    // 1分毎に接続を死活監視する
                    Thread.sleep(1 * 60 * 1000);
                } catch (InterruptedException e) {
                }
            }
        }

        public boolean isStop() {
            return isStop;
        }

        public void setStop(boolean isStop) {
            this.isStop = isStop;
        }
    }

    @Override
    public void destroy() {
        try {
            if (necExecutor != null) {
                necExecutor.stopExecutor();
            }
            
            if (necHandler != null) {
                necHandler.stop();
            }

            try {
                monitor.setStop(true);
                monitor.interrupt();
            } catch (Exception ex) {
            }

        } catch (Exception e) {
            log.error("", e);
        }

    }
}