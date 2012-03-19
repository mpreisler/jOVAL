// Copyright (C) 2011 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.os.windows.remote.system;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnknownHostException;

import org.slf4j.cal10n.LocLogger;

import org.jinterop.dcom.common.JIException;
import org.jinterop.dcom.impls.automation.JIExcepInfo;

import com.h9labs.jwbem.SWbemServices;
import com.h9labs.jwbem.SWbemObjectSet;

import org.joval.intf.io.IFile;
import org.joval.intf.system.IProcess;
import org.joval.intf.util.ILoggable;
import org.joval.io.TailDashF;
import org.joval.os.windows.remote.wmi.scripting.SWbemSecurity;
import org.joval.os.windows.remote.wmi.win32.Win32Process;
import org.joval.os.windows.remote.wmi.win32.Win32ProcessStartup;
import org.joval.util.JOVALMsg;

/**
 * Remote Windows implementation of an IProcess.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
class WindowsProcess implements IProcess, ILoggable {
    private SWbemServices services;
    private Win32ProcessStartup startupInfo;
    private Win32Process process;
    private int pid = 0;
    private String command, cwd;
    private IFile out, err;
    private TailDashF outTail, errTail;
    private boolean running = false;
    private int exitCode = 0;
    private LocLogger logger;

    WindowsProcess(SWbemServices services, String command, String[] env, String cwd, IFile out, IFile err) throws JIException {
	this.services = services;
	this.command = command;
	this.cwd = cwd;
	this.out = out;
	this.err = err;
	startupInfo = new Win32ProcessStartup(services);
	startupInfo.setEnvironmentVariables(env);
	logger = JOVALMsg.getLogger();
    }

    // Implement ILoggable

    public void setLogger(LocLogger logger) {
	this.logger = logger;
    }

    public LocLogger getLogger() {
	return logger;
    }

    // Implement IProcess

    public String getCommand() {
	return command;
    }

    // REMIND (DAS): implement this...
    public void setInteractive(boolean interactive) {
/*
	SWbemSecurity security = new SWbemSecurity(services);
	security.setImpersonationLevel(SWbemSecurity.ImpersonationLevel_IMPERSONATION);
*/
    }

    public void start() throws Exception {
	process = new Win32Process(services);
	outTail = new TailDashF(out);
	outTail.start();
	errTail = new TailDashF(err);
	errTail.start();
	StringBuffer sb = new StringBuffer("cmd /c ").append(command);
	sb.append(" >> ").append(out.getPath());
	sb.append(" 2>> ").append(err.getPath());
	int rc = process.create(sb.toString(), cwd, startupInfo);

	switch(rc) {
	  case Win32Process.SUCCESSFUL_COMPLETION:
	    pid = process.getProcessId();
	    running = true;
	    new Monitor(this).start();
	    break;

	  default:
	    JIExcepInfo error = process.getError();
	    System.out.println("Error code: " + Integer.toHexString(error.getErrorCode()));
	    System.out.println("Description: " + error.getExcepDesc());
	    System.out.println("Source: " + error.getExcepSource());
	    break;
	}
    }

    public InputStream getInputStream() {
	return outTail.getInputStream();
    }

    public InputStream getErrorStream() {
	return errTail.getInputStream();
    }

    public OutputStream getOutputStream() {
	throw new UnsupportedOperationException("Not implemented");
    }

    public void waitFor(long millis) throws InterruptedException {
	long end = Long.MAX_VALUE;
	if (millis > 0) {
	    end = System.currentTimeMillis() + millis;
	}
	while (isRunning() && System.currentTimeMillis() < end) {
	    Thread.sleep(Math.min(end - System.currentTimeMillis(), 250));
	}
    }

    public int exitValue() {
	return exitCode;
    }

    public boolean isRunning() {
	if (running) {
	    String wql = "Select ProcessId from Win32_Process where ProcessId=" + pid;
	    SWbemObjectSet set = services.execQuery(wql);
	    if (set.getSize() == 0) {
		running = false;
	    }
	}
	return running;
    }

    public void destroy() {
	try {
	    process.terminate(1);
	    running = false;
	} catch (JIException e) {
	    logger.warn(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
	}
    }

    // Private

    /**
     * The Monitor class waits for the process to finish for up to an hour, then it cleans up any open tails.
     */
    class Monitor implements Runnable {
	private WindowsProcess p;

	Monitor(WindowsProcess p) {
	    this.p = p;
	}

	void start() {
	    new Thread(this).start();
	}

	public void run() {
	    try {
		p.waitFor(3600000); // 1 hour
		if (p.isRunning()) {
		    p.destroy();
		}
	    } catch (InterruptedException e) {
	    }
	    try {
		synchronized(p.err) {
		    if (p.errTail != null) {
			if (p.errTail.isAlive()) {
			    p.errTail.interrupt();
			}
			p.err.delete();
		    }
		}
	    } catch (IOException e) {
		logger.warn(JOVALMsg.ERROR_IO, p.err.getPath(), e.getMessage());
	    }
	    try {
		synchronized(p.out) {
		    if (p.outTail != null) {
			if (p.outTail.isAlive()) {
			    p.outTail.interrupt();
			}
			p.out.delete();
		    }
		}
	    } catch (IOException e) {
		logger.warn(JOVALMsg.ERROR_IO, p.out.getPath(), e.getMessage());
	    }
	}
    }
}
