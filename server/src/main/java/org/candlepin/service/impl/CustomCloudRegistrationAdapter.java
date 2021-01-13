/**
 * Copyright (c) 2009 - 2020 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.service.impl;

import org.candlepin.service.CloudRegistrationAdapter;
import org.candlepin.service.model.CloudRegistrationInfo;
import org.candlepin.service.exception.CloudRegistrationAuthorizationException;
import org.candlepin.service.exception.MalformedCloudRegistrationException;


/**
 * The custom implementation of the {@link CloudRegistrationAdapter}.
 *
 * This implementation always returns organization id "snowwhite"
 */
public class CustomCloudRegistrationAdapter implements CloudRegistrationAdapter {
    @Override
    public String resolveCloudRegistrationData(CloudRegistrationInfo cloudRegInfo)
        throws CloudRegistrationAuthorizationException, MalformedCloudRegistrationException {

        // do any custom logic here

        return "snowwhite";
    }
}