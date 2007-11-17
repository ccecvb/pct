/*
 * The Apache Software License, Version 1.1
 *
 * Copyright (c) 2002 The Apache Software Foundation.  All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution, if
 *    any, must include the following acknowlegement:
 *       "This product includes software developed by the
 *        Apache Software Foundation (http://www.apache.org/)."
 *    Alternately, this acknowlegement may appear in the software itself,
 *    if and wherever such third-party acknowlegements normally appear.
 *
 * 4. The names "Ant" and "Apache Software
 *    Foundation" must not be used to endorse or promote products derived
 *    from this software without prior written permission. For written
 *    permission, please contact apache@apache.org.
 *
 * 5. Products derived from this software may not be called "Apache"
 *    nor may "Apache" appear in their names without prior written
 *    permission of the Apache Group.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE APACHE SOFTWARE FOUNDATION OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 */
package com.phenix.pct;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;

import java.text.MessageFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Vector;

/**
 * Run a background Progress procedure.
 * 
 * @author <a href="mailto:justus_phenix@users.sourceforge.net">Gilles QUERRET </a>
 * @version $Revision$
 */
public abstract class PCTBgRun extends PCTRun {

    // Internal use : socket communication
    private int port;
    // Internal use : throw BuildException 
    private boolean buildException;
    
    /**
     * Default constructor
     */
    public PCTBgRun() {
        super(true);
    }

    /**
     * PCTBgRun doesn't allow a procedure to be specified. It's always pct/_server.p
     * 
     * @see com.phenix.pct.PCTRun#setProcedure(String)
     * @throws BuildException Always throws a BuildException
     */
    public void setProcedure(String procedure) {
        throw new BuildException(Messages.getString("PCTBgRun.0")); //$NON-NLS-1$
    }

    /**
     * Instantiate your own Listener
     * 
     * @return A listener for your task
     */
    protected abstract PCTListener getListener(PCTBgRun parent) throws IOException;

    /**
     * Do the work
     * 
     * @throws BuildException Something went wrong
     */
    public void execute() throws BuildException {
        PCTListener listener = null;

        // Preparing Exec task
        if (!this.isPrepared()) {
            this.prepareExecTask();
        }

        // Starting the listener thread
        try {
            listener = getListener(this);
            listener.start();
            this.port = listener.getLocalPort();
        } catch (IOException ioe) {
            this.cleanup();
            throw new BuildException(ioe.getMessage());
        }

        super.setProcedure("pct/_server.p"); //$NON-NLS-1$
        this.addParameter(new RunParameter("portNumber", Integer.toString(this.port))); //$NON-NLS-1$
        
        this.preparePropath();
        this.createInitProcedure();
        this.setExecTaskParams();
        exec.createArg().setValue("-p"); //$NON-NLS-1$
        exec.createArg().setValue(this.initProc.getAbsolutePath());
        
        extractPL(pctLib);
        
        // And executes Exec task
        exec.execute();
        cleanup();
        try {
            // Waiting for listener thread to stop
            listener.join();
        } catch (InterruptedException ie) {
            this.cleanup();
            throw new BuildException(ie);
        } 
        if (buildException) throw new BuildException("Build failed");
    }

    
    /**
     * This is a customized copy of PCTRun.createInitProcedure. The only difference is that database connections and
     * propath modifications are not made in this file (it's delayed in the background process).
     */
    private void createInitProcedure() throws BuildException {
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(this.initProc));

            // Progress v8 is unable to write to standard output, so output is redirected in a file,
            // which is parsed in a later stage
            if (this.getProgressProcedures().needRedirector()) {
                outputStreamID = new Random().nextInt() & 0xffff;
                outputStream = new File(
                        System.getProperty("java.io.tmpdir"), "pctOut" + outputStreamID + ".txt"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            bw.write(MessageFormat.format(this.getProgressProcedures().getInitString(),
                    new Object[]{(this.outputStream == null ? null : this.outputStream
                            .getAbsolutePath())}));

            // Defines internal propath
            if (this.internalPropath != null) {
                String[] lst = this.internalPropath.list();
                for (int k = lst.length - 1; k >= 0; k--) {
                    bw.write(MessageFormat.format(this.getProgressProcedures().getPropathString(),
                            new Object[]{escapeString(lst[k]) + File.pathSeparatorChar}));
                }
            }

           // Defines parameters
            if (this.runParameters != null) {
                for (Iterator i = this.runParameters.iterator(); i.hasNext(); ) {
                    RunParameter param = (RunParameter) i.next();
                    if (param.validate()) {
                        bw.write(MessageFormat.format(this.getProgressProcedures().getParameterString(), new Object[]{escapeString(param.getName()), escapeString(param.getValue())}));
                    }
                }
            }

            bw.write(MessageFormat.format(this.getProgressProcedures().getRunString(),
                    new Object[]{escapeString(this.procedure)}));
            bw.write(MessageFormat.format(this.getProgressProcedures().getReturnProc(),
                    new Object[]{escapeString(status.getAbsolutePath())}));

            bw.close();
        } catch (IOException ioe) {
            throw new BuildException();
        }
    }

    /**
     * Listener thread
     * 
     * @author <a href="mailto:justus_phenix@users.sourceforge.net">Gilles QUERRET </a>
     */
    protected abstract class PCTListener extends Thread {
        // Timeout for accept method -- 5 seconds should be enough
        // Increase it if you're doing some debugging
        private final static int TIMEOUT = 5000;

        protected PCTBgRun parent = null;

        private ServerSocket server = null;
        private Socket sock = null;
        private BufferedReader reader = null;
        private BufferedWriter writer = null;

        public PCTListener(PCTBgRun parent) throws IOException {
            this.parent = parent;
            this.server = new ServerSocket(0);
            this.server.setSoTimeout(TIMEOUT);
        }

        public int getLocalPort() {
            return this.server.getLocalPort();
        }

        /**
         * Sends a command to the Progress process, and waits for execution
         * 
         * @param cmd String containing the Progress order (see pct/_server.p)
         * @return True if OK, false if not OK...
         */
        protected boolean sendCommand(String cmd) throws IOException {
            // Check validity
            if ((cmd == null) || (cmd.trim().equals(""))) //$NON-NLS-1$
                return false;

            // Splits Progress command and parameters
            String command = null, param = null;
            int pos = cmd.indexOf(' ');
            if ((pos == -1) || (cmd.length() == pos)) {
                command = cmd.trim();
            } else {
                command = cmd.substring(0, pos).trim();
                param = cmd.substring(pos + 1).trim();
            }

            // First character in capital letters
            if (command.length() == 1) {
                command = command.toUpperCase();
            } else {
                command = command.substring(0, 1).toUpperCase() + command.substring(1);
            }

            writer.write(command + " " + param); //$NON-NLS-1$
            writer.newLine();
            writer.flush();

            // And wait for response
            String returnCode = null;
            List retString = new Vector();
            String s = null;
            int count = 1;
            while (!(s = reader.readLine()).trim().equalsIgnoreCase("END.")) { //$NON-NLS-1$
                if (count++ == 1)
                    returnCode = s;
                else
                    retString.add(s);
            }

            boolean retValue = returnCode.startsWith("OK:"); //$NON-NLS-1$
            try {
                Method m = this.getClass().getMethod("handle" + command, //$NON-NLS-1$
                        new Class[]{String.class, String.class, List.class});
                m.invoke(this, new Object[]{param, returnCode, retString});
            } catch (NoSuchMethodException nsme) {
                log(MessageFormat.format(Messages.getString("PCTBgRun.1"), new Object[]{command}), //$NON-NLS-1$
                        Project.MSG_WARN);
            } catch (InvocationTargetException ite) {
                log(MessageFormat.format(Messages.getString("PCTBgRun.2"), new Object[]{command, //$NON-NLS-1$
                        ite.getCause().getClass().getName(), ite.getCause().getMessage()}),
                        Project.MSG_WARN);
            } catch (Throwable t) {
                log(MessageFormat.format(Messages.getString("PCTBgRun.3"), new Object[]{command, //$NON-NLS-1$
                        t.getClass().getName(), t.getMessage()}), Project.MSG_WARN);
            }

            return retValue;
        }

        /**
         * Generic calls made by PCTBgRun : connect to database and change propath, then pass away
         * to custom method
         */
        public void run() {
            try {
                sock = this.server.accept();
                reader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                writer = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream()));
                // Receive greeting from Progress
                while (!(reader.readLine()).trim().equals("END.")); //$NON-NLS-1$
            } catch (IOException ioe) {
                return;
            }

            try {
                if (dbConnList != null) {
                    for (Iterator iter = dbConnList.iterator(); iter.hasNext();) {
                        PCTConnection dbc = (PCTConnection) iter.next();
                        String s = dbc.createConnectString();
                        sendCommand("CONNECT " + s); //$NON-NLS-1$
                        // FIXME Ajouter les alias
                    }
                }
                if (this.parent.propath != null) {
                    String[] lst = this.parent.propath.list();
                    for (int k = lst.length - 1; k >= 0; k--) {
                        sendCommand("propath " + lst[k]); //$NON-NLS-1$
                    }
                }
                buildException = custom();
                sendCommand("QUIT"); //$NON-NLS-1$
            } 
            catch (Throwable be) {
                this.parent.cleanup();
            }

        }

        /**
         * Handles propath response
         */
        public void handlePropath(String param, String ret, List strings) {
            // Nothing
        }
        
        /**
         * Handles connect response 
         */
        public void handleConnect(String param, String ret, List strings) {
            // Nothing
        }

        /**
         * This is where you code the task's logic
         */
        protected abstract boolean custom() throws IOException;
    }
}