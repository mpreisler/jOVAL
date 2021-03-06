// Copyright (C) 2011 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.intf.cisco.system;

import org.joval.intf.system.IBaseSession;
import org.joval.intf.net.INetconf;

/**
 * A representation of an IOS command-line session.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public interface IIosSession extends IBaseSession {
    /**
     * Property indicating the number of milliseconds to wait for a command to begin to return data.
     */
    public static final String PROP_READ_TIMEOUT = "ios.read.timeout";

    /**
     * Get a NETCONF channel to the device.
     */
    INetconf getNetconf();

    /**
     * Retrieve "show tech-support" data from the device.
     */
    ITechSupport getTechSupport();
}
