/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.binding.draytonwiser.internal.config;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/**
 * @author Andrew Schofield - Initial contribution
 */
public class RSSI {

    @SerializedName("Current")
    @Expose
    private Integer current;
    @SerializedName("Min")
    @Expose
    private Integer min;
    @SerializedName("Max")
    @Expose
    private Integer max;

    public Integer getCurrent() {
        return current;
    }

    public Integer getMin() {
        return min;
    }

    public Integer getMax() {
        return max;
    }

}
