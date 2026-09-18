const axios = require('axios');
const { faker } = require('@faker-js/faker');

const GATEWAY_URL = 'http://localhost:8888';
const KEYCLOAK_TOKEN_URL = 'http://localhost:8180/realms/ecom-bank/protocol/openid-connect/token';

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function getAuthToken() {
  console.log('Authenticating as manager with Keycloak to obtain bearer token...');
  try {
    const params = new URLSearchParams();
    params.append('client_id', 'ecom-frontend');
    params.append('username', 'manager');
    params.append('password', 'manager123');
    params.append('grant_type', 'password');

    const res = await axios.post(KEYCLOAK_TOKEN_URL, params, {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
    });
    console.log('Successfully acquired bearer token.');
    return res.data.access_token;
  } catch (err) {
    console.error('Failed to obtain token from Keycloak:', err?.response?.data || err.message);
    throw err;
  }
}

async function seed() {
  const token = await getAuthToken();
  const headers = { Authorization: `Bearer ${token}` };

  console.log('Checking idempotency guard...');
  try {
    const existing = await axios.get(`${GATEWAY_URL}/customer-service/api/customers`, { headers });
    const customersList = existing.data._embedded ? existing.data._embedded.customers : (Array.isArray(existing.data) ? existing.data : []);
    if (customersList.length > 10) {
      console.warn(`[ABORT] Database already contains ${customersList.length} customers. Seeding aborted to prevent duplication.`);
      return;
    }
  } catch (err) {
    console.log('No existing customers found or endpoint check skipped, proceeding with seeding...');
  }

  console.log('Starting data seeding via API Gateway...');

  // 1. Create 100 Customers (Spring Data REST: POST /api/customers)
  const customerIds = [];
  console.log('\n--- Creating 100 Customers ---');
  for (let i = 1; i <= 100; i++) {
    try {
      const res = await axios.post(`${GATEWAY_URL}/customer-service/api/customers`, {
        name: faker.person.fullName(),
        email: faker.internet.email()
      }, { headers });
      
      const customerId = res.data.id || (res.data._links?.self?.href ? parseInt(res.data._links.self.href.split('/').pop()) : i);
      customerIds.push(customerId);
      console.log(`[Customer ${i}/100] Created ID: ${customerId} (${res.data.name})`);
    } catch (err) {
      console.error(`[Customer ${i}] Failed:`, err?.response?.data || err.message);
      if (err?.response?.data) console.error(JSON.stringify(err.response.data, null, 2));
    }
    await delay(50);
  }

  // 2. Create 200 Accounts (2 per customer)
  const accountIds = [];
  console.log('\n--- Creating 200 Accounts (2 per customer) ---');
  for (const customerId of customerIds) {
    for (let j = 0; j < 2; j++) {
      try {
        const res = await axios.post(`${GATEWAY_URL}/ledger-service/api/accounts`, {
          customerId: customerId
        }, { headers });
        const accountId = res.data.accountId;
        accountIds.push(accountId);
        console.log(`[Account] Created ${accountId} for Customer ${customerId}`);
      } catch (err) {
        console.error(`[Account] Failed for Customer ${customerId}:`, err?.response?.data || err.message);
        if (err?.response?.data) console.error(JSON.stringify(err.response.data, null, 2));
      }
      await delay(50);
    }
  }

  // 3. Set Initial Balances ($1,000 to $15,000) via POST /api/transactions
  console.log('\n--- Setting Initial Balances for Accounts ---');
  for (const accountId of accountIds) {
    const initialBalance = parseFloat(faker.finance.amount({ min: 1000, max: 15000, dec: 2 }));
    try {
      await axios.post(`${GATEWAY_URL}/ledger-service/api/transactions`, {
        type: 'CREDIT',
        accountId: accountId,
        amount: initialBalance,
        description: 'Initial deposit seeding'
      }, { headers });
      console.log(`[Deposit] Credited $${initialBalance} to Account ${accountId}`);
    } catch (err) {
      console.error(`[Deposit] Failed for Account ${accountId}:`, err?.response?.data || err.message);
      if (err?.response?.data) console.error(JSON.stringify(err.response.data, null, 2));
    }
    await delay(50);
  }

  // 4. Create 50 Products (Spring Data REST: POST /api/products)
  console.log('\n--- Creating 50 Products ---');
  for (let i = 1; i <= 50; i++) {
    try {
      const res = await axios.post(`${GATEWAY_URL}/inventory-service/api/products`, {
        name: faker.commerce.productName(),
        price: parseFloat(faker.commerce.price({ min: 10, max: 500, dec: 2 })),
        quantity: faker.number.int({ min: 10, max: 100 })
      }, { headers });
      console.log(`[Product ${i}/50] Created: ${res.data.name} ($${res.data.price}, Qty: ${res.data.quantity})`);
    } catch (err) {
      console.error(`[Product ${i}] Failed:`, err?.response?.data || err.message);
      if (err?.response?.data) console.error(JSON.stringify(err.response.data, null, 2));
    }
    await delay(50);
  }

  console.log('\n========================================');
  console.log(`SUCCESSFULLY SEEDED:`);
  console.log(`- Customers created: ${customerIds.length}`);
  console.log(`- Accounts created: ${accountIds.length}`);
  console.log(`- Products created: 50`);
  console.log('========================================');
}

seed().catch((err) => {
  console.error('Seeding script failed:', err);
});
