package com.mirrorlife.cube.core.gateway.nec;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IoSession;

import com.mirrorlife.cube.core.gateway.nec.NECExecutor;
import com.mirrorlife.cube.core.gateway.nec.NECHandler.SendTagThread;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class NECHandler extends IoHandlerAdapter {
	/**
     * 
     */

    private SendTagThread sendTagThread;
    private NECExecutor executor;
    private ConcurrentHashMap<String, ConcurrentHashMap<String, String>> antennaTagMaps;

    private int millis;
    public long lastUpdate;

    public NECHandler(NECExecutor executor) {
        this.executor = executor;
        antennaTagMaps = new ConcurrentHashMap<>();

        try {

            millis = Integer.valueOf(executor.server.getGatewayInfo().getJsonConfMap().get("TAG_REFRESH_TIME_LAG").getValue().toString());
            String[] antennas = executor.server.getGatewayInfo().getJsonConfMap().get("NEC_ANTENNA_PORT_LIST").getValue().toString().split(",");

            for (String port : antennas) {
                antennaTagMaps.put(port, new ConcurrentHashMap<String, String>());
            }
        } catch (Exception e) {
            log.error("", e);
        }

        sendTagThread = new SendTagThread();
        sendTagThread.start();
    }


    private final static Pattern TAG_PATTERN = Pattern.compile("\\w{2}000\\w{2}007000000\\w{20}(0[0,1,9])\\w{6}(\\w{28})");
    //02000580070000007BEA0F009D430003010001003400E200471836006023302C010F99BB

    /* return msg format : tagIDList00;300ED89F335000400131AD33A7A9;300ED89F335000400131ACFADCFD; */
    private static final String TAG_RETURN_PATTERN = "tagIDList";

    @Override
    public void messageReceived(IoSession session, Object message) {

        lastUpdate = System.currentTimeMillis();
        Matcher m = TAG_PATTERN.matcher((String) message);

        while (m.find()) {
            int groups = m.groupCount();
            if (groups == 2) {
                if (antennaTagMaps.get(m.group(1)) == null) {
                    antennaTagMaps.put(m.group(1), new ConcurrentHashMap<>());
                }
                antennaTagMaps.get(m.group(1)).put(m.group(2), m.group(2));
            }
        }
    }

    class SendTagThread extends Thread {
        private boolean isStop;

        @Override
        public void run() {
            while (!isStop()) {
                try {
                    sleep(millis);
                    processMsg();
                } catch (InterruptedException e) {
                }
            }
            log.warn("ParseMsgThread is closed.");
        }

        public boolean isStop() {
            return isStop;
        }

        public void setStop(boolean isStop) {
            this.isStop = isStop;
        }
    }

    private void processMsg() {

        for (String antennaID : antennaTagMaps.keySet()) {
            String rtn = TAG_RETURN_PATTERN + antennaID + ";";
            ConcurrentHashMap<String, String> antennaTagMap = antennaTagMaps.get(antennaID);
            for (String key : antennaTagMap.keySet()) {
                rtn += key + ";";
            }

            this.executor.messageReceived(rtn);
            antennaTagMap.clear();
        }
    }


    @Override
    public void messageSent(IoSession session, Object message) {
        log.info("Message is sent: " + message);
    }


    @Override
    public void sessionOpened(IoSession session) {
        log.debug("NECHandler session opened.");
    }

    @Override
    public void sessionClosed(IoSession session) {
        log.debug("NECHandler session closed.");
    }


    @Override
    public void exceptionCaught(IoSession session, Throwable cause) {
        log.error("NECHandler", cause);
    }


    public void stop() {
        if (sendTagThread != null && sendTagThread.isAlive()) {
            sendTagThread.setStop(true);
        }
    }
}
