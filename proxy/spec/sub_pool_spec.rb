require 'openssl'
require 'candlepin_scenarios'

describe 'Sub-Pool' do
  include CandlepinMethods
  it_should_behave_like 'Candlepin Scenarios'

  it 'inherits order number extension from parent pool' do
    # ===== Given =====
    owner = create_owner 'test_owner'
    derived_product = create_product()
    parent_product = create_product(nil, nil, {:attributes => {
            'user_license' => 'unlimited',
            'user_license_product' => derived_product.id,
            'requires_consumer_type' => 'person'
    }})

    # Create a subscription
    subscription = @cp.create_subscription(owner.id, parent_product.id, 5)
    @cp.refresh_pools(owner.key, false)

    # Set up user
    billy = user_client(owner, 'billy')

    # ===== When =====
    # Register his personal consumer
    billy_consumer = consumer_client(billy, 'billy_consumer', :person)

    # Subscribe to the parent pool
    billy_consumer.consume_product parent_product.id

    # Now register a system
    system1 = consumer_client(billy, 'system1')

    # And subscribe to the created sub-pool
    system1.consume_product derived_product.id

    # ===== Then =====
    entitlement_cert = system1.list_certificates.first
    cert = OpenSSL::X509::Certificate.new(entitlement_cert.cert)

    # TODO:  This magic OID should be refactored...
    order_number = get_extension(cert, '1.3.6.1.4.1.2312.9.4.2')

    order_number.should == subscription.id.to_s
  end

end
