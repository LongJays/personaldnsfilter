package dnsfilter.remote;


import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;

import dnsfilter.ConfigurationAccess;
import util.AsyncLogger;
import util.GroupedLogger;
import util.Logger;
import util.LoggerInterface;
import util.Utils;

public class RemoteAccessServer implements Runnable {

    private static int sessionId=0;
    String user;
    String pwd;

    boolean stopped = false;
    private ServerSocket server;
    private HashMap sessions = new HashMap<Integer, RemoteSession>();

    public RemoteAccessServer(int port, String user, String pwd) throws IOException{
        this.user=user;
        this.pwd=pwd;
        server = new ServerSocket(port);
        new Thread (this).start();
    }


    private String readStringFromStream(InputStream in, byte[] buf) throws IOException {
       int r = Utils.readLineBytesFromStream(in, buf,true, false);

       if (r == -1)
           throw new EOFException("Stream is closed!");

       return new String(buf,0,r).trim();
    }

    @Override
    public void run() {
        while (!stopped) {
            try {
                Socket con = server.accept();
                Logger.getLogger().logLine("New Remote Session from :"+con);
                try {
                    InputStream in = con.getInputStream();
                    byte[] buf = new byte[1024];
                    String version = readStringFromStream(in, buf);
                    String user = readStringFromStream(in, buf);
                    String pwd = readStringFromStream(in, buf);
                    String option = readStringFromStream(in, buf);
                    check(version, user, pwd);

                    if (option.equals("new_session")) {
                        //create and start session
                        sessionId++;
                        new RemoteSession(con, sessionId);
                        OutputStream out = con.getOutputStream();
                        out.write("OK\n".getBytes());
                        out.write((sessionId+"\n").getBytes());
                        out.write((ConfigurationAccess.getLocal().getVersion() + "\n").getBytes());
                        out.write((ConfigurationAccess.getLocal().getLastDNSAddress() + "\n").getBytes());
                        out.flush();
                    }
                    else if (option.equals("reconnect_session")) {
                        int id;
                        try {
                            id = Integer.parseInt(readStringFromStream(in, buf));
                        } catch (Exception e) {
                            throw new IOException(e);
                        }
                        RemoteSession session = (RemoteSession) sessions.get(id);
                        if (session == null)
                            throw new IOException("Reconnect session not found:"+id);
                        else {
                            session.reconnectSession(con);
                            OutputStream out = con.getOutputStream();
                            out.write("OK\n".getBytes());
                            out.flush();
                        }
                    }
                    else throw new IOException("Invalid option: "+option);

                } catch (IOException e) {
                    con.getOutputStream().write(e.toString().getBytes());
                    con.getOutputStream().flush();
                    Utils.closeSocket(con);
                    throw e;
                }
            } catch (IOException e) {
                Logger.getLogger().logLine("RemoteServerException: "+e.toString());
            }
        }
    }

    private void check(String version, String user, String pwd) throws IOException {
        if (user.equals(this.user) && pwd.equals(this.pwd))
            return;
        else
            throw new IOException("Logon failed");
    }


    public void stop() {
        stopped = true;
        RemoteSession[] remoteSessions = (RemoteSession[]) sessions.values().toArray(new RemoteSession[0]);
        for (int i = 0; i < remoteSessions.length; i++ )
            remoteSessions[i].killSession();
        try {
            server.close();
        } catch (IOException e) {
            Logger.getLogger().logException(e);
        }
    }


    /*********************************************************/
    /*********** Inner class RemoteSession *******************/
    /*********************************************************/
    private class RemoteSession implements Runnable {

        int id;
        Socket socket;
        LoggerInterface remoteLogger;
        boolean killed = false;
        boolean doReconnect = false;
        DataOutputStream out;
        DataInputStream in;


        private RemoteSession(Socket con, int id) throws IOException{
            this.id = id;
            this.socket = con;
            out = new DataOutputStream(con.getOutputStream());
            in = new DataInputStream(con.getInputStream());
            sessions.put(id, this);
            new Thread(this).start();
        }

        public void killSession(){
            if (killed)
                return;
            killed = true;
            if (remoteLogger!= null) {
                remoteLogger.closeLogger();
                ((GroupedLogger) Logger.getLogger()).detachLogger(remoteLogger);
            }

            Utils.closeSocket(socket);
            sessions.remove(id);

        }

        public void reconnectSession(Socket con) throws IOException{
           doReconnect = true;
           Socket old = this.socket;
           this.socket = con;
           out = new DataOutputStream(con.getOutputStream());
           in = new DataInputStream(con.getInputStream());
           Utils.closeSocket(old);
        }

        @Override
        public void run() {
            byte[] buf = new byte[1024];
            String action="";
            while (!killed) {
                try {
                    action = readStringFromStream(in, buf);
                    if (action.equals("attach"))
                        attachStream();
                    else if (action.equals("releaseConfiguration()"))
                        killSession();
                    else executeAction(action);
                } catch (ConfigurationAccess.ConfigurationAccessException e) {
                    Logger.getLogger().logLine("RemoteServer Exception processing "+action+"! " + e.toString());
                } catch (IOException e) {
                    if (!doReconnect) {
                        if (!killed) {
                            Logger.getLogger().logLine("Exception during RemoteServer Session read! " + e.toString());
                            killSession();
                            break;
                        }
                    } else {
                        Logger.getLogger().logLine("Reconnected Remote!");
                        doReconnect=false;
                    }
                }
            }
            Logger.getLogger().logLine("Remote Session closed! "+socket);
        }

        private void executeAction(String action) throws IOException{

            try {

                if (action.equals("getConfig()")) {
                    Properties config = ConfigurationAccess.getLocal().getConfig();
                    out.write("OK\n".getBytes());
                    ObjectOutputStream objout = new ObjectOutputStream(out);
                    objout.writeObject(config);
                    objout.flush();
                } else if (action.equals("readConfig()")) {
                    byte[] result = ConfigurationAccess.getLocal().readConfig();
                    out.write("OK\n".getBytes());
                    out.writeInt(result.length);
                    out.write(result);
                    out.flush();
                } else if (action.equals("updateConfig()")) {
                    byte[] cfg = new byte[in.readInt()];
                    in.readFully(cfg);
                    ConfigurationAccess.getLocal().updateConfig(cfg);
                    out.write("OK\n".getBytes());
                    out.flush();
                } else if (action.equals("getAdditionalHosts()")) {
                    int limit = in.readInt();
                    byte[] result = ConfigurationAccess.getLocal().getAdditionalHosts(limit);
                    out.write("OK\n".getBytes());
                    out.writeInt(result.length);
                    out.write(result);
                    out.flush();
                } else if (action.equals("updateAdditionalHosts()")) {
                    byte[] cfg = new byte[in.readInt()];
                    in.readFully(cfg);
                    ConfigurationAccess.getLocal().updateAdditionalHosts(cfg);
                    out.write("OK\n".getBytes());
                    out.flush();
                } else if (action.equals("updateFilter()")) {
                    String entries = Utils.readLineFromStream(in);
                    boolean filter = Boolean.parseBoolean(Utils.readLineFromStream(in));
                    ConfigurationAccess.getLocal().updateFilter(entries, filter);
                    out.write("OK\n".getBytes());
                    out.flush();
                } else if (action.equals("restart()")) {
                    ConfigurationAccess.getLocal().restart();
                    out.write("OK\n".getBytes());
                    out.flush();
                } else if (action.equals("stop()")) {
                    ConfigurationAccess.getLocal().stop();
                    out.write("OK\n".getBytes());
                    out.flush();
                } else if (action.equals("getFilterStatistics()")) {
                    long[] result = ConfigurationAccess.getLocal().getFilterStatistics();
                    out.write("OK\n".getBytes());
                    out.writeLong(result[0]);
                    out.writeLong(result[1]);
                    out.flush();
                } else if (action.equals("triggerUpdateFilter()")) {
                    ConfigurationAccess.getLocal().triggerUpdateFilter();
                    out.write("OK\n".getBytes());
                    out.flush();
                } else if (action.equals("doBackup()")) {
                    ConfigurationAccess.getLocal().doBackup();
                    out.write("OK\n".getBytes());
                    out.flush();
                } else if (action.equals("doRestore()")) {
                    ConfigurationAccess.getLocal().doRestore();
                    out.write("OK\n".getBytes());
                    out.flush();
                } else if (action.equals("doRestoreDefaults()")) {
                    ConfigurationAccess.getLocal().doRestoreDefaults();
                    out.write("OK\n".getBytes());
                    out.flush();
                } else if (action.equals("wakeLock()")) {
                    ConfigurationAccess.getLocal().wakeLock();
                    out.write("OK\n".getBytes());
                    out.flush();
                } else if (action.equals("releaseWakeLock()")) {
                    ConfigurationAccess.getLocal().releaseWakeLock();
                    out.write("OK\n".getBytes());
                    out.flush();
                } else
                    throw new ConfigurationAccess.ConfigurationAccessException("Unknown action: " + action);

            } catch (ConfigurationAccess.ConfigurationAccessException e) {
                out.write((e.getMessage().replace("\n", "\t")+"\n").getBytes());
                out.flush();
            }
        }

        private void attachStream() throws IOException{
            remoteLogger = new AsyncLogger(new LoggerInterface() {

                public void sendLog(int type, String txt) {
                    try {

                        //info about open connections
                        out.writeShort(RemoteAccessClient.UPD_CON_CNT);
                        byte[] msg = (ConfigurationAccess.getLocal().openConnectionsCount()+"").getBytes();
                        out.writeShort(msg.length);
                        out.write(msg);

                        //last DNS
                        out.writeShort(RemoteAccessClient.UPD_DNS);
                        msg = (ConfigurationAccess.getLocal().getLastDNSAddress()).getBytes();
                        out.writeShort(msg.length);
                        out.write(msg);

                        //the log
                        msg = txt.getBytes();
                        out.writeShort(type);
                        out.writeShort(msg.length);
                        out.write(msg);

                        out.flush();

                    } catch (IOException e) {
                        killSession();
                        Logger.getLogger().logLine("Exception during remote logging! "+e.toString());
                    }
                }

                @Override
                public void logLine(String txt) {
                    sendLog(RemoteAccessClient.LOG_LN, txt);
                }

                @Override
                public void logException(Exception e) {
                    StringWriter str = new StringWriter();
                    e.printStackTrace(new PrintWriter(str));
                    log(str.toString()+"\n");
                }

                @Override
                public void log(String txt) {
                    sendLog(RemoteAccessClient.LOG, txt);

                }

                @Override
                public void message(String txt) {
                    sendLog(RemoteAccessClient.LOG_MSG, txt);

                }

                @Override
                public void closeLogger() {

                }
            });

            ((GroupedLogger)Logger.getLogger()).attachLogger(remoteLogger);

            out.write("OK\n".getBytes());
            out.flush();
        }
    }

    /*********** end of inner class RemoteSession *******************/

}