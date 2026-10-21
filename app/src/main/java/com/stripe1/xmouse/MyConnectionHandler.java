package com.stripe1.xmouse;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.stripe1.xmouse.secrets.PrivateKeyImportException;
import com.stripe1.xmouse.secrets.AndroidCredentials;
import com.stripe1.xmouse.secrets.CredentialUi;
import com.stripe1.xmouse.secrets.AuthenticationMaterial;
import com.stripe1.xmouse.secrets.BoundedSshKey;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;


public class MyConnectionHandler implements CommandTransport {


    public JSch jsch=new JSch();
    public Session session =null;
    private Channel channel=null;
    private InputStream in = null;
    private PipedOutputStream pin =null;

    //public PrintStream shellStream;
    //public ByteArrayOutputStream os = null;


    private static final String TAG = "MyConnectionHandler";
    private Activity myActivity;

    private MyInterface myInterface;
    private final CredentialUi credentialUi;
    private int authenticationGeneration;
    private boolean readingCredentials;

    public MyConnectionHandler(Activity a, MyInterface b){

        myInterface = b;
        myActivity = a;
        credentialUi = new CredentialUi(a);
    }
    public void startCredentialUi() { credentialUi.start(); }
    public void stopCredentialUi() { if (!credentialUi.hasPicker()) { authenticationGeneration++; readingCredentials = false; credentialUi.stop(); } }
    public int credentialGeneration() { return credentialUi.getGeneration(); }
    public boolean credentialsCurrent(int token) { return credentialUi.isCurrent(token); }
    public boolean onCredentialResult(int code, int result, Intent data) { return credentialUi.onActivityResult(code, result, data); }

    public void xMouseTryConnect(){ //change connection state
        if (readingCredentials) return;
        if(session==null || !session.isConnected()){

            if(TextUtils.isEmpty(MainActivity.setting_user) || TextUtils.isEmpty(MainActivity.setting_host)){

                Toast.makeText(myActivity, "A connection setting is blank", Toast.LENGTH_LONG).show();
                return;
            }

            final int attempt = ++authenticationGeneration;
            readingCredentials = true;
            final int token = credentialUi.getGeneration();
            AndroidCredentials.get(myActivity).execute(() -> {
                try {
                    AndroidCredentials.get(myActivity).prepare();
                    AndroidCredentials.get(myActivity).authenticate(credentialUi, (material, error) -> myActivity.runOnUiThread(() -> {
                        if (attempt == authenticationGeneration) readingCredentials = false;
                        if (attempt != authenticationGeneration || !credentialUi.isCurrent(token)) { if (material != null) material.close(); return; }
                        if (material == null) { if (error != null) Toast.makeText(myActivity, error, Toast.LENGTH_LONG).show(); return; }
                        try { beginSsh(material); } catch (Exception ignored) { material.close(); credentialUi.message(); }
                    }));
                } catch (Exception ignored) {
                    myActivity.runOnUiThread(() -> { if (attempt == authenticationGeneration) readingCredentials = false; if (credentialUi.isCurrent(token)) credentialUi.message(); });
                }
            });
        }else{
            xMouseDisconnect();
        }
    }

    private void beginSsh(AuthenticationMaterial material) {
            SshConnectTask t = new SshConnectTask(myActivity, material, MainActivity.setting_shell) {
                protected void onPostExecute(String result) {
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }
                    if(result.isEmpty()){
                        //addTextToTextView("SSH connection failed");

                        Toast.makeText(myActivity, "Connection failed, check settings and try again", Toast.LENGTH_LONG).show();

                    }else{

                        if(result.equals("true")){
                            Toast.makeText(myActivity, "Connection established", Toast.LENGTH_LONG).show();
                            //import command to execute to porperly set the display window to be used
                            //':0.0' is the default of the user currently logged in
                            executeShellCommand(MainActivity.setting_xdotool_initial);

                        }else{

                            Toast.makeText(myActivity, "Error: "+result, Toast.LENGTH_LONG).show();
                        }
                    }
                    myInterface.performCallback();//refresh connection icon
                }
            };
            //dt.setCont(mCont);
            t.execute("");
    }

    public class SshConnectTask extends AsyncTask<String, String, String> {

        private String mUser = "";
        private String mHost ="";
        private int mPort =22;
        private final AuthenticationMaterial material;
        private String mShell = "";
        private String xhost = "127.0.0.1";
        private int xport = 0;
        private Activity a;

        public SshConnectTask(Activity a, AuthenticationMaterial material, String shell){
            this.a=a;
            this.material = material;
            this.mUser=material.getHost().getUser();
            this.mHost=material.getHost().getHost();
            this.mPort=Integer.parseInt(material.getHost().getPort());
            this.mShell = shell;

            dialog = new ProgressDialog(a);
        }

        /** progress dialog to show user that the backup is processing. */
        protected ProgressDialog dialog;
        protected String doInBackground(String... params) {
            try{
                Log.d(TAG, "connection_attempt");
                session= jsch.getSession(mUser, mHost, mPort);

                if(material.getUseKey()){
                    try {
                        jsch.removeAllIdentity();
                        BoundedSshKey.addIdentity(jsch, material.getPrivateKey(), material.getPassphrase());
                        session.setConfig("PreferredAuthentications", "publickey");
                    } catch (JSchException ignored) {
                        // Never expose a key parser/provider message through the legacy generic logger.
                        throw new PrivateKeyImportException(PrivateKeyImportException.Reason.INVALID_KEY);
                    }
                }else{
                    session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
                    session.setPassword(material.getPassword());
                }
                session.setX11Host(xhost);
                session.setX11Port(xport + 6000);
                session.setConfig("StrictHostKeyChecking", "no");
                //session.setServerAliveInterval(1000);
                session.connect();


                if (TextUtils.isEmpty(mShell)) {
                    channel = session.openChannel("shell");
                } else {
                    channel = session.openChannel("exec");
                    ((ChannelExec) channel).setCommand(mShell);
                }
                //channel.setXForwarding(true);

                //channel.setInputStream(System.in);

                //channel.connect(3000);

                /*os = new ByteArrayOutputStream();
                channel.setOutputStream(os);
                //shellStream = new PrintStream(channel.getOutputStream());  // printStream for convenience
                */

                //channel.setOutputStream(os, true);
                in = new PipedInputStream();
                pin = new PipedOutputStream((PipedInputStream) in);
                channel.setInputStream(in);
                channel.connect(3000);


            }catch(PrivateKeyImportException e){
                return "Private key unavailable or invalid ("+e.getReason().name()+"). Open Settings > Private Key to re-import; check Optional Key Passphrase. Existing configuration was kept.";
            }catch(Exception e){
                return "Connection or authentication failed. Existing configuration was kept.";
            }finally{
                material.close();
                try { jsch.removeAllIdentity(); } catch (JSchException ignored) { }
            }
            if(session.isConnected()){

                return String.valueOf(session.isConnected());
            }else{
                return "Not connected, no exception thrown.";
            }
        }
        @Override
        protected void onProgressUpdate(String... text) {
            //finalResult.setText(text[0]);
            // Things to be done while execution of long running operation is in
            // progress. For example updating ProgessDialog
        }
        @Override
        protected void onPreExecute() {
            this.dialog.setMessage("Connecting to "+mUser+"@"+mHost+":"+mPort);
            this.dialog.show();
        }
    }
    public void xMouseDisconnect(){
        authenticationGeneration++;
        readingCredentials = false;
        credentialUi.stop();
        credentialUi.start();
        if(session!=null){
            if(session.isConnected()){
                channel.disconnect();
                session.disconnect();
                session=null;
                channel = null;

            }
        }
        //showConnectionStat();
        //MyCallback.callbackCall();
        myInterface.performCallback();//refresh connection icon
    }

    public boolean executeExecCommand(final String cmd){

        if(session==null || cmd == null || cmd.length()==0){
            return false;
        }
        if(session.isConnected()){
            //log.append(cmd);
            SshExecTask  t = (SshExecTask) new SshExecTask(myActivity,cmd) {
                protected void onPostExecute(final String result) {
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }

                    AlertDialog.Builder builder = new AlertDialog.Builder(myActivity);
                    builder.setTitle(cmd);
                    builder.setMessage(result);
                    builder.setNeutralButton("Ok", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                        }
                    });
                    builder.setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                                // Gets a handle to the clipboard service.
                                ClipboardManager clipboard = (ClipboardManager) myActivity.getSystemService(Context.CLIPBOARD_SERVICE);
                                // Creates a new text clip to put on the clipboard
                                ClipData clip = ClipData.newPlainText("xmouse script output", result);
                                clipboard.setPrimaryClip(clip);

                        }
                    });

                    AlertDialog dialog = builder.create();
                    dialog.show();
                    myInterface.performCallback();//refresh connection icon

                }
            };
            //dt.setCont(mCont);
            t.execute("");
            return true;
        }
        return false;
    }

    @Override
    public boolean executeShellCommand(String cmd){

        MainActivity.recentCmdTextView.setText(cmd);
        if(session==null){
            return false;
        }
        if(session.isConnected() && channel.isConnected()){
            try {
                cmd=cmd+"\r\n";
                pin.write(cmd.getBytes());
                pin.flush();
                return true;
            } catch (IOException ignored) {
                Log.w(TAG, "command_send_failed");
                Toast.makeText(myActivity, "Command could not be sent.", Toast.LENGTH_SHORT).show();
            }
        }
        return false;
    }
    public class SshExecTask extends AsyncTask<String, String, String> {

        String cmd;
        public SshExecTask(Activity a, String cmd){
            this.a=a;
            this.cmd=cmd;
            dialog = new ProgressDialog(a);
        }
        private Activity a;
        /** progress dialog to show user that the backup is processing. */
        protected ProgressDialog dialog;
        protected String doInBackground(String... params) {
            StringBuilder log = new StringBuilder();

            try {

                Channel channel = session.openChannel("exec");
                ((ChannelExec)channel).setCommand(cmd);

                // X Forwarding
                //channel.setXForwarding(true);

                //channel.setInputStream(System.in);
                channel.setInputStream(null);
                BufferedReader r = new BufferedReader(new InputStreamReader(((ChannelExec) channel).getErrStream()));
                //InputStream in=channel.getInputStream();
                BufferedReader r2 = new BufferedReader(new InputStreamReader(channel.getInputStream()));
                channel.connect();
                StringBuilder total = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    total.append(line);
                }
                while ((line = r2.readLine()) != null) {
                    total.append(line);
                }
                channel.disconnect();
                //session.disconnect();
                log.append(total.toString());

            } catch (Exception ignored){
                Log.w(TAG, "exec_command_failed");
                log.append("Command execution failed.");
            }
            return log.toString();
        }
        @Override
        protected void onProgressUpdate(String... text) {
            //finalResult.setText(text[0]);
            // Things to be done while execution of long running operation is in
            // progress. For example updating ProgessDialog
        }
        @Override
        protected void onPreExecute() {
            this.dialog.setMessage("Executing: "+cmd);
            this.dialog.show();
        }
    }

    public class SshScpTask extends AsyncTask<String, String, String> {

        String cmd;
        public SshScpTask(Activity a,String W,String H){
            this.a=a;
            this.cmd="scp -f test.jpg";

        }
        private Activity a;

        protected String doInBackground(String... params) {
            StringBuilder log = new StringBuilder();
            try {
                String lfile = "mouse_bg.jpg";
			//String prefix=null;
			  //if(new File(lfile).isDirectory()){
			    //prefix=lfile+File.separator;
			  //}

                Channel channel = session.openChannel("exec");
                ((ChannelExec)channel).setCommand(cmd);


                // get I/O streams for remote scp
                OutputStream out=channel.getOutputStream();
                InputStream in=channel.getInputStream();
                channel.connect();

                byte[] buf=new byte[1024];

                // send '\0'
                buf[0]=0; out.write(buf, 0, 1); out.flush();

                while(true){
                    int c=checkAck(in);
                    if(c!='C'){
                        break;
                    }

                    // read '0644 '
                    in.read(buf, 0, 5);

                    long filesize=0L;
                    while(true){
                        if(in.read(buf, 0, 1)<0){
                            // error
                            break;
                        }
                        if(buf[0]==' ')break;
                        filesize=filesize*10L+(long)(buf[0]-'0');
                    }

                    String file=null;
                    for(int i=0;;i++){
                        in.read(buf, i, 1);
                        if(buf[i]==(byte)0x0a){
                            file=new String(buf, 0, i);
                            break;
                        }
                    }

                    // send '\0'
                    buf[0]=0; out.write(buf, 0, 1); out.flush();


                    // read a content of lfile
                    //FileOutputStream fos=new FileOutputStream(prefix==null ? lfile : prefix+file);
                    FileOutputStream fos = a.openFileOutput(lfile, Context.MODE_PRIVATE);
                    int foo;
                    while(true){
                        if(buf.length<filesize) foo=buf.length;
                        else foo=(int)filesize;
                        foo=in.read(buf, 0, foo);
                        if(foo<0){
                            // error
                            break;
                        }
                        fos.write(buf, 0, foo);
                        filesize-=foo;
                        if(filesize==0L) break;
                    }
                    fos.close();
                    fos=null;

                    if(checkAck(in)!=0){
                        Log.d(TAG, "scp_complete");
                    }

                    // send '\0'
                    buf[0]=0; out.write(buf, 0, 1); out.flush();

                }
            } catch (JSchException ignored) {
                Log.w(TAG, "scp_transport_failed");
            } catch (IOException ignored) {
                Log.w(TAG, "scp_io_failed");
            }
            return log.toString();
        }


        int checkAck(InputStream in) throws IOException {
            int b=in.read();
            // b may be 0 for success,
            //          1 for error,
            //          2 for fatal error,
            //          -1
            if(b==0) return b;
            if(b==-1) return b;

            if(b==1 || b==2){
                int c;
                do {
                    c=in.read();
                }
                while(c!='\n' && c!=-1);
                if(b==1){ // error
                    Log.e(TAG, "scp_remote_error");
                }
                if(b==2){ // fatal error
                    Log.e(TAG, "scp_remote_fatal_error");
                }
            }
            return b;
        }
    }
}
