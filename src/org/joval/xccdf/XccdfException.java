// Copyright (C) 2012 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.xccdf;

/**
 * An exception class for XCCDF parsing.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public class XccdfException extends Exception {
    public XccdfException(String message) {
	super(message);
    }

    public XccdfException(Exception e) {
	super(e);
    }
}
