// Copyright (C) 2012 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.os.juniper.system;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Vector;

import org.slf4j.cal10n.LocLogger;

import org.joval.intf.juniper.system.IJunosSession;
import org.joval.intf.juniper.system.ISupportInformation;
import org.joval.util.JOVALMsg;
import org.joval.util.SafeCLI;

/**
 * A structure that stores the result from the "request support information" command.  Remarkably, the structure
 * of this information is very similar to that of the Cisco IOS "show tech-support" command.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public class SupportInformation implements ISupportInformation {
    private Hashtable<String, List<String>> data = new Hashtable<String, List<String>>();
    private LocLogger logger;

    /**
     * Load support information from a stream source.
     */
    public SupportInformation(InputStream in) throws IOException {
	logger = JOVALMsg.getLogger();
	BufferedReader br = null;
	try {
	    br = new BufferedReader(new InputStreamReader(in));
	    List<String> lines = new Vector<String>();
	    String line = null;
	    while ((line = br.readLine()) != null) {
		lines.add(line);
	    }
	    load(lines);
	} finally {
	    if (br != null) {
		try {
		    br.close();
		} catch (IOException e) {
		    logger.warn(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION, e));
		}
	    }
	}
    }

    /**
     * Gather support information from the session.
     */
    public SupportInformation(IJunosSession session) throws Exception {
	logger = session.getLogger();
	long readTimeout = session.getProperties().getLongProperty(IJunosSession.PROP_READ_TIMEOUT);
	load(SafeCLI.multiLine("request support information", session, readTimeout));
    }

    // Implement ISupportInformation

    public Collection<String> getShowSubcommands() {
	HashSet<String> subcommands = new HashSet<String>();
	for (String heading : getHeadings()) {
	    if (heading.toLowerCase().startsWith("show")) {
		subcommands.add(heading);
	    }
	}
	return subcommands;
    }

    public Collection<String> getHeadings() {
	return data.keySet();
    }

    public List<String> getData(String heading) throws NoSuchElementException {
	List<String> list = data.get(heading);
	if (list == null) {
	    throw new NoSuchElementException(heading);
	} else {
	    return list;
	}
    }

    // Private

    private String prompt = null;

    /**
     * Populate the structure from a sequential list of lines.
     */
    protected void load(List<String> lines) {
	//
	// Discover the prompt
	//
	int len = lines.size();
	for (int i=0; i < len; i++) {
	    String line = lines.get(i);
	    int ptr = 0;
	    if ((ptr = line.indexOf("show")) != -1) {
		prompt = line.substring(0, ptr).trim();
	    }
	}

	String heading = null;
	List<String> body = null;
	for (String line : lines) {
	    if (isHeading(line)) {
		if (heading != null) {
		    data.put(heading, body);
		}
		heading = getHeading(line);
		body = new Vector<String>();
	    } else if (heading == null) {
		if (line.length() > 0) {
		    logger.debug(JOVALMsg.ERROR_JUNOS_SUPPORT_ORPHAN, line);
		}
	    } else {
		if (body.size() == 0 && line.length() == 0) {
		    // skip empty lines under header
		} else {
		    body.add(line);
		}
	    }
	}
    }

    private boolean isHeading(String line) {
	return line.startsWith(prompt);
    }

    private String getHeading(String line) {
	return line.substring(prompt.length()).trim();
    }
}
