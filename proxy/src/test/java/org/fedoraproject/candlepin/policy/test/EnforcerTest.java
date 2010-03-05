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
package org.fedoraproject.candlepin.policy.test;

import static org.junit.Assert.*;

import org.fedoraproject.candlepin.model.Consumer;
import org.fedoraproject.candlepin.model.Pool;
import org.fedoraproject.candlepin.model.Owner;
import org.fedoraproject.candlepin.model.Product;
import org.fedoraproject.candlepin.policy.Enforcer;
import org.fedoraproject.candlepin.policy.ValidationResult;
import org.fedoraproject.candlepin.policy.js.JavascriptEnforcer;
import org.fedoraproject.candlepin.policy.js.PostEntHelper;
import org.fedoraproject.candlepin.policy.js.PreEntHelper;
import org.fedoraproject.candlepin.policy.js.RuleExecutionException;
import org.fedoraproject.candlepin.test.DatabaseTestFixture;
import org.fedoraproject.candlepin.test.DateSourceForTesting;
import org.fedoraproject.candlepin.test.TestDateUtil;
import org.fedoraproject.candlepin.test.TestUtil;

import org.junit.Before;
import org.junit.Test;

import java.util.Date;

public class EnforcerTest extends DatabaseTestFixture {

    private Enforcer enforcer;
    private Owner owner;
    private Consumer consumer;
    private static final String LONGEST_EXPIRY_PRODUCT = "LONGEST001";
    private static final String HIGHEST_QUANTITY_PRODUCT = "QUANTITY001";
    private static final String BAD_RULE_PRODUCT = "BADRULE001";
    private static final String NO_RULE_PRODUCT = "NORULE001";

    @Before
    public void createEnforcer() {
        owner = TestUtil.createOwner();
        ownerCurator.create(owner);

        consumer = TestUtil.createConsumer(owner);
        consumerTypeCurator.create(consumer.getType());
        consumerCurator.create(consumer);

        PreEntHelper preHelper = new PreEntHelper();
        PostEntHelper postHelper = new PostEntHelper(productAdapter);
        enforcer = new JavascriptEnforcer(new DateSourceForTesting(2010, 1, 1),
                rulesCurator, preHelper, postHelper, productAdapter);
    }

    // grrr. have to test two conditions atm: sufficient number of entitlements
    // *when* pool has not expired
    //
    // shouldPassValidationWhenSufficientNumberOfEntitlementsIsAvailableAndNotExpired
    @Test
    public void passValidationEnoughNumberOfEntitlementsIsAvailableAndNotExpired() {
        ValidationResult result = enforcer.pre(
                TestUtil.createConsumer(),
                entitlementPoolWithMembersAndExpiration(1, 2, expiryDate(2010,
                        10, 10))).getResult();
        assertTrue(result.isSuccessful());
        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    public void shouldFailValidationWhenNoEntitlementsAreAvailable() {
        ValidationResult result = enforcer.pre(
                TestUtil.createConsumer(),
                entitlementPoolWithMembersAndExpiration(1, 1, expiryDate(2010,
                        10, 10))).getResult();
        assertFalse(result.isSuccessful());
        assertTrue(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    public void shouldFailWhenEntitlementsAreExpired() {
        ValidationResult result = enforcer.pre(
                TestUtil.createConsumer(),
                entitlementPoolWithMembersAndExpiration(1, 2, expiryDate(2000,
                        1, 1))).getResult();
        assertFalse(result.isSuccessful());
        assertTrue(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    private Date expiryDate(int year, int month, int day) {
        return TestDateUtil.date(year, month, day);
    }

    private Pool entitlementPoolWithMembersAndExpiration(
            final int currentMembers, final int maxMembers, Date expiry) {
        return new Pool(new Owner(), new Product("label", "name")
                .getId(), new Long(maxMembers), new Date(), expiry) {

            {
                setCurrentMembers(currentMembers);
            }
        };
    }

    @Test
    public void testSelectBestPoolLongestExpiry() {
        Pool pool1 = new Pool(owner, LONGEST_EXPIRY_PRODUCT,
            new Long(5), TestUtil.createDate(2000, 02, 26),
            TestUtil.createDate(2050, 02, 26));
        Pool pool2 = new Pool(owner, LONGEST_EXPIRY_PRODUCT,
            new Long(5), TestUtil.createDate(2000, 02, 26),
            TestUtil.createDate(2051, 02, 26));
        Pool desired = new Pool(owner, LONGEST_EXPIRY_PRODUCT,
            new Long(5), TestUtil.createDate(2000, 02, 26),
            TestUtil.createDate(2060, 02, 26));
        Pool pool3 = new Pool(owner, LONGEST_EXPIRY_PRODUCT,
            new Long(5), TestUtil.createDate(2000, 02, 26),
            TestUtil.createDate(2055, 02, 26));
        poolCurator.create(pool1);
        poolCurator.create(pool2);
        poolCurator.create(desired);
        poolCurator.create(pool3);

        Pool result = enforcer.selectBestPool(consumer,
            LONGEST_EXPIRY_PRODUCT, poolCurator.listAvailableEntitlementPools(consumer));
        assertEquals(desired.getId(), result.getId());
    }

    @Test
    public void testSelectBestPoolMostAvailable() {
        Pool pool1 = new Pool(owner, HIGHEST_QUANTITY_PRODUCT,
            new Long(5), TestUtil.createDate(2000, 02, 26),
            TestUtil.createDate(2050, 02, 26));
        Pool desired = new Pool(owner, HIGHEST_QUANTITY_PRODUCT,
            new Long(500), TestUtil.createDate(2000, 02, 26),
            TestUtil.createDate(2051, 02, 26));
        Pool pool2 = new Pool(owner, HIGHEST_QUANTITY_PRODUCT,
            new Long(5), TestUtil.createDate(2000, 02, 26),
            TestUtil.createDate(2060, 02, 26));
        poolCurator.create(pool1);
        poolCurator.create(pool2);
        poolCurator.create(desired);

        Pool result = enforcer.selectBestPool(consumer,
            HIGHEST_QUANTITY_PRODUCT, poolCurator.listAvailableEntitlementPools(consumer));
        assertEquals(desired.getId(), result.getId());
    }

    @Test
    public void testSelectBestPoolNoPools() {
        // There are no pools for the product in this case:
        Pool result = enforcer.selectBestPool(consumer,
            HIGHEST_QUANTITY_PRODUCT, poolCurator.listAvailableEntitlementPools(consumer));
        assertNull(result);
    }

    @Test(expected = RuleExecutionException.class)
    public void testSelectBestPoolBadRule() {
        Pool pool1 = new Pool(owner, BAD_RULE_PRODUCT,
            new Long(5), TestUtil.createDate(2000, 02, 26),
            TestUtil.createDate(2050, 02, 26));
        poolCurator.create(pool1);

        enforcer.selectBestPool(consumer, BAD_RULE_PRODUCT,
            poolCurator.listAvailableEntitlementPools(consumer));
    }

    @Test
    public void testSelectBestPoolDefaultRule() {
        Pool pool1 = new Pool(owner, NO_RULE_PRODUCT,
            new Long(5), TestUtil.createDate(2000, 02, 26),
            TestUtil.createDate(2050, 02, 26));
        Pool pool2 = new Pool(owner, NO_RULE_PRODUCT,
            new Long(5), TestUtil.createDate(2000, 02, 26),
            TestUtil.createDate(2060, 02, 26));
        poolCurator.create(pool1);
        poolCurator.create(pool2);

        Pool result = enforcer.selectBestPool(consumer,
            NO_RULE_PRODUCT, poolCurator.listAvailableEntitlementPools(consumer));
        assertEquals(pool1.getId(), result.getId());
    }
}
