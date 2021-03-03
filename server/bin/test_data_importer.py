#!/usr/bin/env python
from __future__ import print_function, division, absolute_import

"""
Injects synthetic data from one or more JSON files into Candlepin for testing
"""

import datetime
import logging
import json
import os
import random
import re
import requests
import string
import sys
import time

from optparse import OptionParser



# Disable HTTP warnings that'll be kicked out by urllib (used internally by requests)
requests.packages.urllib3.disable_warnings()

# Setup logging
LOGLVL_TRACE = 5
logging.addLevelName(LOGLVL_TRACE, 'TRACE')
logging.basicConfig(stream=sys.stdout, level=logging.INFO, format="%(asctime)-15s %(levelname)-7s %(name)-16s -- %(message)s")

# We don't care about urllib/requests logging
logging.getLogger("requests").setLevel(logging.WARNING)
logging.getLogger("urllib3").setLevel(logging.WARNING)

log = logging.getLogger('test_data_importer')


# TODO: There's likely a better python way of doing this that I don't know, but all the ways that I've
# learned make shallow copies of the entire dictionary to do the check which seems rather wasteful to me,
# so we go old school here.
def ci_in(dict, search):
    search = search.lower()

    for key in dict:
        if key.lower() == search:
            return True

    return False

# Merges two dictionaries, converting the keys in the source dictionary from snake case to camel case
# where appropriate.
def safe_merge(source, dest, include=None, exclude=None):
    def handle_match(match):
        return "%s%s" % (match.group(1), match.group(2).upper())

    for key, value in source.items():
        key = re.sub('(\w)_(\w)', handle_match, key)

        if (include is None or key in include) and (exclude is None or key not in exclude):
            dest[key] = value

    return dest

def random_id(prefix, suffix_length=8):
    suffix = ''.join(random.choice(string.digits) for i in range(suffix_length))
    return "%s-%s" % (prefix, suffix)



class Candlepin:
    # Server configuration
    host = 'localhost'
    port = 8443
    username = 'admin'
    password = 'admin'

    status_cache = None
    hosted_test = None


    def __init__(self, host, port, username, password):
        self.host = host
        self.port = port
        self.username = username
        self.password = password

        self.determine_mode()

    def build_url(self, endpoint):
        # Remove leading slash if it exists
        if endpoint and endpoint[0] == '/':
            endpoint = endpoint[1:]

        return "https://%s:%d/candlepin/%s" % (self.host, self.port, endpoint)

    def get(self, endpoint, params=None, headers=None, content_type='text/plain', accept_type='application/json'):
        return self.request('GET', endpoint, None, params, headers, content_type, accept_type)

    def post(self, endpoint, data=None, params=None, headers=None, content_type='application/json', accept_type='application/json'):
        return self.request('POST', endpoint, data, params, headers, content_type, accept_type)

    def put(self, endpoint, data=None, params=None, headers=None, content_type='application/json', accept_type='application/json'):
        return self.request('PUT', endpoint, data, params, headers, content_type, accept_type)

    def delete(self, endpoint, data=None, params=None, headers=None, content_type='application/json', accept_type='application/json'):
        return self.request('DELETE', endpoint, data, params, headers, content_type, accept_type)

    def request(self, req_type, endpoint, data=None, params=None, headers=None, content_type='application/json', accept_type='application/json'):
        if params is None: params = {}
        if headers is None: headers = {}

        # Add our content-type and accept type if they weren't explicitly provided in the headers already
        if not ci_in(headers, 'Content-Type'): headers['Content-Type'] = content_type
        if not ci_in(headers, 'Accept'): headers['Accept'] = accept_type

        log.log(LOGLVL_TRACE, "Sending request: %s %s (params: %s, headers: %s, data: %s)" % (req_type, endpoint, params, headers, data))

        response = requests.request(req_type, self.build_url(endpoint),
            json=data,
            auth=(self.username, self.password),
            params=params,
            headers=headers,
            verify=False)

        response.raise_for_status()
        return response

    def status(self):
        if self.status_cache is None:
            self.status_cache = self.get('status').json()

        return self.status_cache

    def determine_mode(self):
        if self.hosted_test is None:
            self.hosted_test = not self.status()['standalone']

            if self.hosted_test:
                try:
                    self.get('hostedtest/alive', accept_type='text/plain')
                except requests.exceptions.HTTPError:
                    self.hosted_test = False

        return self.hosted_test

    # Candlepin specific functions
    def create_owner(self, owner_data):
        owner = {
            'key': owner_data['name'],
            'displayName': owner_data['displayName']
        }

        if 'contentAccessModeList' in owner_data:
            owner['contentAccessModeList'] = owner_data['contentAccessModeList']

        if 'contentAccessMode' in owner_data:
            owner['contentAccessMode'] = owner_data['contentAccessMode']

        owner = self.post('owners', owner).json()

        # If we're operating in hosted-test mode, also create the org upstream for consistency
        if self.hosted_test:
            self.post('hostedtest/owners', { 'key': owner['key'] })

        return owner

    def create_user(self, user_data):
        user = safe_merge(user_data, {}, include=['username', 'password'])
        user['superAdmin'] = user_data['superadmin'] if 'superadmin' in user_data else False

        return self.post('users', user).json()

    def create_role(self, role_name, permissions):
        role = {
            'name': role_name,
            'permissions': permissions
        }

        return self.post('roles', role).json()

    def add_role_to_user(self, username, role_name):
        return self.post("roles/%s/users/%s" % (role_name, username)).json()

    def create_content(self, owner_key, content_data):
        content = safe_merge(content_data, {})

        if self.hosted_test:
            url = 'hostedtest/content'
        else:
            url = "owners/%s/content" % (owner_key)

        return self.post(url, content).json()

    def create_product(self, owner_key, product_data):
        attributes = product_data['attributes'] if 'attributes' in product_data else {}
        attributes['version'] = product_data['version'] if 'version' in product_data else "1.0"
        attributes['variant'] = product_data['variant'] if 'variant' in product_data else "ALL"
        attributes['arch'] = product_data['arch'] if 'arch' in product_data else "ALL"
        attributes['type'] = product_data['type'] if 'type' in product_data else "SVC"

        branding = []
        content = product_data['content'] if 'content' in product_data else []
        dependent_products = product_data['dependencies'] if 'dependencies' in product_data else []
        relies_on = product_data['relies_on'] if 'relies_on' in product_data else []
        provided_products = []
        derived_product = None

        # correct provided products and derived product
        if 'provided_products' in product_data:
            provided_products = [ { 'id': value } for value in product_data['provided_products'] ]

        if 'derived_product_id' in product_data:
            derived_product = { 'id': product_data['derived_product_id'] }

        # Add branding to some of the products
        # TODO: FIXME: couldn't we just do this in the test data instead?
        if provided_products and 'OS' in product_data['name'] and 'type' in product_data and product_data['type'] == 'MKT':
            branding.append({
                'productId': provided_products[0]['id'],
                'type': 'OS',
                'name': "Branded %s" % (product_data['name'])
            })

        product = {
            'id': product_data['id'],
            'name': product_data['name'],
            'multiplier': product_data['multiplier'] if 'multiplier' in product_data else 1,
            'attributes': attributes,
            'dependentProductIds': dependent_products,
            'relies_on': relies_on,
            'branding': branding,
            'providedProducts': provided_products,
            'derivedProduct': derived_product
        }

        # Determine URL and submit
        if self.hosted_test:
            url = 'hostedtest/products'
        else:
            url = "owners/%s/products" % (owner_key)

        return self.post(url, product).json()

    def get_product_cert(self, owner_key, product_id):
        return self.get("owners/%s/products/%s/certificate" % (owner_key, product_id)).json()

    def add_content_to_product(self, owner_key, product_id, content_map):
        if self.hosted_test:
            url = "hostedtest/products/%s/content" % (product_id)
        else:
            url = "owners/%s/products/%s/batch_content" % (owner_key, product_id)

        return self.post(url, content_map).json()

    def create_pool(self, owner_key, pool_data):
        start_date = pool_data['start_date'] if 'start_date' in pool_data else datetime.datetime.now()
        end_date = pool_data['end_date'] if 'end_date' in pool_data else start_date + datetime.timedelta(days=365)

        pool = {
            'startDate': start_date,
            'endDate': end_date,
            'quantity': pool_data['quantity'] if 'quantity' in pool_data else 1
        }

        # Merge in the other keys we care about for pools
        keys = ['branding', 'contractNumber', 'accountNumber', 'orderNumber', 'subscriptionId', 'upstreamPoolId']
        safe_merge(pool_data, pool, include=keys)

        if self.hosted_test:
            # Need to set a subscription ID if we're generating this upstream
            pool['id'] = random_id('upstream')

            # Product is an object in hosted test mode
            pool['product'] = { 'id': pool_data['product_id'] }

            # The owner must be set in the subscription data for upstream subscriptions
            pool['owner'] = { 'key': owner_key }

            url = 'hostedtest/subscriptions'
        else:
            # Copy over the pool (special case because hosted test data is different)
            pool['productId'] = pool_data['product_id']

            # Generate fields that make this look like an upstream subscription
            if not 'subscriptionId' in pool:
                pool['subscriptionId'] = random_id('srcsub')

            if 'subscription_subkey' in pool_data:
                pool['subscriptionSubKey'] = pool_data['subscription_subkey']
            elif 'subscriptionSubKey' in pool_data:
                pool['subscriptionSubKey'] = pool_data['subscriptionSubKey']
            else:
                pool['subscriptionSubKey'] = 'master'

            if not 'upstreamPoolId' in pool:
                pool['upstreamPoolId'] = random_id('upstream')

            url = "owners/%s/pools" % (owner_key)

        return self.post(url, pool).json()

    def list_pools(self, owner_key):
        return self.get("owners/%s/pools" % (owner_key)).json()

    def create_activation_key(self, owner_key, key_data):
        key = safe_merge(key_data, {})
        return self.post("owners/%s/activation_keys" % (owner_key), key).json()

    def add_pool_to_activation_key(self, key_id, pool_id, quantity=None):
        url = "activation_keys/%s/pools/%s" % (key_id, pool_id)
        params = {}

        if quantity is not None:
            params['quantity'] = quantity

        return self.post(url, None, params).json()

    def refresh_pools(self, owner_key, wait_for_completion=True):
        job_status = self.put("owners/%s/subscriptions" % (owner_key), None, params={ 'lazy_regen': True }).json()

        if job_status and wait_for_completion:
            finished_states = ['ABORTED', 'FINISHED', 'CANCELED', 'FAILED']

            while job_status['state'] not in finished_states:
                time.sleep(1)
                job_status = self.get(job_status['statusPath']).json()

            # Clean up job status
            # self.delete('jobs', {'id': job_status['id']})

        return job_status



# Gather and order content in a way that ensures linear creation is safe
def gather_content(data, hosted_test):
    content = []

    if hosted_test:
        # In hosted mode, we don't need per-org product or content definitions, so only
        # add the global content once
        if 'content' in data:
            content.extend([(None, value) for value in data['content']])

        if 'owners' in data:
            for owner in data['owners']:
                if 'content' in owner:
                    content.extend([(None, value) for value in owner['content']])

    else:
        # In standalone mode, we can only add stuff if we have an org to add it to
        if 'owners' in data:
            for owner in data['owners']:
                owner_key = owner['name']

                if 'content' in data:
                    content.extend([(owner_key, value) for value in data['content']])

                if 'content' in owner:
                    content.extend([(owner_key, value) for value in owner['content']])

    return content

# Gather and order products in a way that ensures linear creation is safe
def gather_products(data, hosted_test):
    def order_products(owner_key, product_map):
        ordered = []
        processed_pids = []

        def traverse_product(product):
            if product['id'] in processed_pids:
                return

            if 'derived_product_id' in product:
                if product['derived_product_id'] not in product_map:
                    raise ReferenceError("product references a non-existent product: %s (%s) => %s" % (product['name'], product['id'], product['derived_product_id']))

                traverse_product(product_map[product['derived_product_id']])

            if 'provided_products' in product:
                for pid in product['provided_products']:
                    if pid not in product_map:
                        raise ReferenceError("product references a non-existent product: %s (%s) => %s" % (product['name'], product['id'], pid))

                    traverse_product(product_map[pid])

            processed_pids.append(product['id'])
            ordered.append((owner_key, product))

        for product in product_map.values():
            traverse_product(product)

        return ordered

    products = []

    if hosted_test:
        product_map = {}

        # In hosted mode, we don't need per-org products, so we only add the global products once
        if 'products' in data:
            for product in data['products']:
                product_map[product['id']] = product

        if 'owners' in data:
            for owner in data['owners']:
                if 'products' in owner:
                    for product in owner['products']:
                        product_map[product['id']] = product

        products.extend(order_products(None, product_map))
    else:
        # In standalone mode, we can only add stuff if we have an org to add it to
        if 'owners' in data:
            for owner in data['owners']:
                owner_key = owner['name']
                product_map = {}

                if 'products' in data:
                    for product in data['products']:
                        product_map[product['id']] = product

                if 'products' in owner:
                    for product in owner['products']:
                        product_map[product['id']] = product

                products.extend(order_products(owner_key, product_map))

    return products

# Performs content mapping for a given product
def map_content_to_product(owner_key, product_data):
    content_map = {}

    if 'content' in product_data:
        # test data stores this as an array of arrays for reasons. Convert it to a dictionary and pass it
        # to Candlepin
        for content_ref in product_data['content']:
            content_map[content_ref[0]] = content_ref[1]

    if content_map:
        cp.add_content_to_product(owner_key, product_data['id'], content_map)


# Process a given JSON file, creating the test data it defines
def process_file(cp, options, filename):
    with open(filename) as file:
        data = json.load(file)

    now = datetime.datetime.now()

    owners = {}
    activation_keys = []
    engineering_products = {}

    log.info("Importing data from file: %s" % (filename))


    # Create owners
    if 'owners' in data:
        print('')
        log.info("Creating %d owner(s)..." % (len(data['owners'])))
        for owner_data in data['owners']:
            log.info("  Owner: %s  [display name: %s]" % (owner_data['name'], owner_data['displayName']))
            owner = cp.create_owner(owner_data)

            owners[owner['key']] = owner_data
            activation_keys.append((owner['key'], "default_key", None))
            activation_keys.append((owner['key'], "awesome_os_pool", None))

    # Create users
    if 'users' in data:
        print('')
        log.info("Creating %d user(s)..." % (len(data['users'])))
        for user_data in data['users']:
            log.info("  User: %s  [password: %s, superuser: %s" % (user_data['username'], user_data['password'], user_data['superadmin'] if 'superadmin' in user_data else False))
            user = cp.create_user(user_data)

    # Create roles
    if 'roles' in data:
        print('')
        log.info("Creating %d role(s)..." % (len(data['roles'])))
        for role_data in data['roles']:
            permissions = role_data['permissions']
            users = role_data['users']

            log.info("  Role: %s  (permissions: %d, users: %d)" % (role_data['name'], len(permissions), len(users)))

            # Correct & list permissions
            for perm_data in permissions:
                # convert owner keys to owner objects:
                if type(perm_data['owner']) != dict:
                    perm_data['owner'] = { 'key': perm_data['owner'] }

                log.info("    Permission: [type: %s, owner: %s, access: %s]" % (perm_data['type'], perm_data['owner'], perm_data['access']))

            role = cp.create_role(role_data['name'], permissions)

            # Add role to users
            for user_data in users:
                log.info("    Applying to user: %s" % (user_data['username']))
                cp.add_role_to_user(user_data['username'], role_data['name'])


    # Create content
    print('')
    content_data = gather_content(data, cp.hosted_test)

    log.info("Creating %d content..." % (len(content_data)))
    for owner_key, content in content_data:
        cp.create_content(owner_key, content)

    # Create products
    print('')
    product_data = gather_products(data, cp.hosted_test)

    log.info("Creating %d product(s)..." % (len(product_data)))
    for owner_key, product in product_data:
        log.info("  Product: %s [name: %s, owner: %s]" % (product['id'], product['name'], owner_key if owner_key else '-GLOBAL-'))
        cp.create_product(owner_key, product)

        # Link products to any content they reference
        map_content_to_product(owner_key, product)

    # Create pools/subscriptions
    print('')
    log.info("Creating subscriptions...")

    for owner_key, owner_data in owners.items():
        account_number = ''.join(random.choice(string.digits) for i in range(10))
        order_number = random_id('order')

        products = []
        pools = []

        if 'products' in data:
            products.extend(data['products'])

        if 'products' in owner:
            products.extend(owner_data['products'])

        for product in products:
            if 'type' in product and product['type'] == 'MKT':

                if 'quantity' in product:
                    small_quantity = int(product['quantity'])
                    large_quantity = small_quantity * 10 if small_quantity > 0 else 10
                else:
                    small_quantity = 5
                    large_quantity = 50

                start_date = (now + datetime.timedelta(days=-5)).isoformat()
                end_date = (now + datetime.timedelta(days=360)).isoformat()

                # Small quantity pool
                pools.append({
                    'product_id': product['id'],
                    'quantity': small_quantity,
                    'contract_number': 0,
                    'account_number': account_number,
                    'order_number': order_number,
                    'start_date': start_date,
                    'end_date': end_date
                })

                # Large quantity pool
                pools.append({
                    'product_id': product['id'],
                    'quantity': large_quantity,
                    'contract_number': 0,
                    'account_number': account_number,
                    'order_number': order_number,
                    'start_date': start_date,
                    'end_date': end_date
                })

                # Future pool
                pools.append({
                    'product_id': product['id'],
                    'quantity': large_quantity,
                    'contract_number': 0,
                    'account_number': account_number,
                    'order_number': order_number,
                    'start_date': (now + datetime.timedelta(days=30)).isoformat(),
                    'end_date': end_date
                })

            else:
                # Product is an engineering product. Flag the pid/owner combo so we can fetch the cert later
                engineering_products[product['id']] = owner_key

        log.info("  Creating %d subscription(s) for owner %s..." % (len(pools), owner_key))
        for pool in pools:
            cp.create_pool(owner_key, pool)

        # Refresh (if necessary)
        if cp.hosted_test:
            log.info("  Refreshing owner: %s" % (owner_key))
            cp.refresh_pools(owner_key)


    # Create activation keys
    for owner_key in owners:
        pools = cp.list_pools(owner_key)
        activation_keys.extend([(owner_key, "%s-%s-key-%s" % (owner_key, pool['productId'], pool['id']), pool) for pool in pools])

    if activation_keys:
        print('')
        log.info("Creating %d activation keys..." % (len(activation_keys)))
        for owner_key, key_name, pool in activation_keys:
            log.info("  Activation key: %s [owner: %s, pool: %s]" % (key_name, owner_key, pool['id'] if pool is not None else 'n/a'))

            key = cp.create_activation_key(owner_key, {'name': key_name})
            if pool is not None:
                cp.add_pool_to_activation_key(key['id'], pool['id'])


    # Fetch entitlements/certs for engineering products
    if engineering_products:
        print('')
        log.info("Fetching certs for %d engineering products..." % (len(engineering_products)))

        # make sure the cert directory exists
        if not os.path.exists(options.cert_dir):
            os.makedirs(options.cert_dir)

        for pid, owner_key in engineering_products.items():
            try:
                # This could 404 if the product did not get pulled down during refresh (hosted mode)
                # because it's not linked/used by anything. We'll issue a warning in this case.
                product_cert = cp.get_product_cert(owner_key, pid)
                cert_filename = "%s/%s.pem" % (options.cert_dir, pid)

                with open(cert_filename, 'w+') as file:
                    log.info("  Writing certificate: %s" % (cert_filename))
                    file.write(product_cert['cert'])
            except requests.exceptions.HTTPError as e:
                if e.response.status_code == 404:
                    log.warn("  Skipping certificate for unused product: %s" % (pid))
                else:
                    raise e

        print('')
        log.info("Finished importing data from file: %s" % (filename))



# Parse CLI options
def parse_options():
    usage = "usage: %prog [options] [JSON_FILES]"
    parser = OptionParser(usage=usage)

    parser.add_option("--debug", action="store_true", default=False,
        help="Enables debug output")
    parser.add_option("--trace", action="store_true", default=False,
        help="Enables trace output; implies --debug")

    parser.add_option("--username", action="store", default='admin',
        help="The username to use when making requests to Candlepin; defaults to 'admin'")
    parser.add_option("--password", action="store", default='admin',
        help="The password to use when making requests to Candlepin; defaults to 'admin'")
    parser.add_option("--host", action="store", default='localhost',
        help="The hostname/address of the Candlepin server; defaults to 'localhost'")
    parser.add_option("--port", action="store", default=8443,
        help="The port to use when connecting to Candlepin; defaults to 8443")

    parser.add_option("--cert_dir", action="store", default="generated_certs",
        help="The directory in which to store generated product certificates; defaults to 'generated_certs'")

    (options, args) = parser.parse_args()

    # Set logging level as appropriate
    if options.trace:
        log.setLevel(LOGLVL_TRACE)
    elif options.debug:
        log.setLevel(logging.DEBUG)

    if len(args) < 1:
        log.debug("No test data files provided, defaulting to 'test_data.json'")

        filename = 'test_data.json'
        if sys.path[0]:
            filename = "%s/%s" % (sys.path[0], filename)

        args = [filename]

    return (options, args)



# Execute bits
try:
    options, files = parse_options()

    log.info("Connecting to Candlepin @ %s:%d" % (options.host, options.port))
    cp = Candlepin(options.host, options.port, options.username, options.password)

    log.info("Importing test data from %d file(s)..." % (len(files)))
    for file in files:
        process_file(cp, options, file)

    log.info("Complete. Files imported: %d" % (len(files)))

except requests.exceptions.ConnectionError as e:
    log.error("Connection error: %s" % (e))
    exit(1)
