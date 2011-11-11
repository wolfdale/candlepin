/**
 * Copyright (c) 2009 Red Hat, Inc.
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
package org.fedoraproject.candlepin.policy.js.compliance;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.fedoraproject.candlepin.model.Entitlement;

/**
 * ComplianceStatus
 *
 * Represents the compliance status for a given consumer. Carries information
 * about which products are fully entitled, not entitled, or partially entitled. (stacked)
 */
public class ComplianceStatus {

    private Date date;
    private Date compliantUntil;
    private Set<String> nonCompliantProducts;
    private Map<String, Set<Entitlement>> compliantProducts;
    private Map<String, Set<Entitlement>> partiallyCompliantProducts; // stacked

    private Map<String, Set<Entitlement>> partialStacks;

    public ComplianceStatus(Date date) {
        this.date = date;

        this.nonCompliantProducts = new HashSet<String>();
        this.compliantProducts = new HashMap<String, Set<Entitlement>>();
        this.partiallyCompliantProducts = new HashMap<String, Set<Entitlement>>();
        this.partialStacks = new HashMap<String, Set<Entitlement>>();
    }

    /**
     * @return Map of compliant product IDs and the entitlements that provide them.
     */
    public Map<String, Set<Entitlement>> getCompliantProducts() {
        return compliantProducts;
    }

    /**
     *
     * @return Date this compliance status was checked for.
     */
    public Date getDate() {
        return date;
    }

    /**
     * @return Set of product IDs installed on the consumer, but not provided by any
     * entitlement. (not even partially)
     */
    public Set<String> getNonCompliantProducts() {
        return nonCompliantProducts;
    }

    public void addNonCompliantProduct(String productId) {
        this.nonCompliantProducts.add(productId);
    }

    /**
     * @return Map of compliant product IDs and the entitlements that partially
     * provide them. (i.e. partially stacked)
     */
    public Map<String, Set<Entitlement>> getPartiallyCompliantProducts() {
        return partiallyCompliantProducts;
    }

    public void addPartiallyCompliantProduct(String productId, Entitlement entitlement) {
        if (!partiallyCompliantProducts.containsKey(productId)) {
            partiallyCompliantProducts.put(productId, new HashSet<Entitlement>());
        }
        partiallyCompliantProducts.get(productId).add(entitlement);
    }

    public void addCompliantProduct(String productId, Entitlement entitlement) {
        if (!compliantProducts.containsKey(productId)) {
            compliantProducts.put(productId, new HashSet<Entitlement>());
        }
        compliantProducts.get(productId).add(entitlement);
    }

    /**
     * @return Map of stack ID to entitlements for each partially completed stack.
     * This will contain all the entitlements in the partially compliant list, but also
     * entitlements which are partially stacked but do not provide any installed product.
     *
     */
    public Map<String, Set<Entitlement>> getPartialStacks() {
        return partialStacks;
    }

    public void addPartialStack(String stackId, Entitlement entitlement) {
        if (!partialStacks.containsKey(stackId)) {
            partialStacks.put(stackId, new HashSet<Entitlement>());
        }
        partialStacks.get(stackId).add(entitlement);
    }

    public Date getCompliantUntil() {
        return this.compliantUntil;
    }

    public void setCompliantUntil(Date date) {
        this.compliantUntil = date;
    }

    public boolean isCompliant() {
        return nonCompliantProducts.isEmpty() && partiallyCompliantProducts.isEmpty();
    }
}
