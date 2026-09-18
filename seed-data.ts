import axios from 'axios';
import { faker } from '@faker-js/faker';

const GATEWAY_URL = 'http://localhost:8888';

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function seed() {
  console.log('Starting data seeding via API Gateway...');

  // 1. Create 100 Customers
  const customerIds: number[] = [];
  console.log('\n--- Creating 100 Customers ---');
  for (let i = 1; i <= 100; i++) {
    try {
      const res = await axios.post(`${GATEWAY_URL}/customer-service/api/customers`, {
        name: faker.person.fullName(),
        email: faker.internet.email()
      });
      const customerId = res.data.id;
      customerIds.push(customerId);
      console.log(`[Customer ${i}/100] Created ID: ${customerId} (${res.data.name})`);
    } catch (err: any) {
      console.error(`[Customer ${i}] Failed:`, err?.response?.data || err.message);
    }
    await delay(50);
  }

  // 2. Create 200 Accounts (2 per customer)
  const accountIds: string[] = [];
  console.log('\n--- Creating 200 Accounts (2 per customer) ---');
  for (const customerId of customerIds) {
    for (let j = 0; j < 2; j++) {
      try {
        const res = await axios.post(`${GATEWAY_URL}/ledger-service/api/accounts`, {
          customerId: customerId
        });
        const accountId = res.data.accountId;
        accountIds.push(accountId);
        console.log(`[Account] Created ${accountId} for Customer ${customerId}`);
      } catch (err: any) {
        console.error(`[Account] Failed for Customer ${customerId}:`, err?.response?.data || err.message);
      }
      await delay(50);
    }
  }

  // 3. Set Initial Balances ($1,000 to $15,000)
  console.log('\n--- Setting Initial Balances for Accounts ---');
  for (const accountId of accountIds) {
    const initialBalance = parseFloat(faker.finance.amount({ min: 1000, max: 15000, dec: 2 }));
    try {
      await axios.post(`${GATEWAY_URL}/ledger-service/api/transactions`, {
        type: 'CREDIT',
        accountId: accountId,
        amount: initialBalance,
        description: 'Initial deposit seeding'
      });
      console.log(`[Deposit] Credited $${initialBalance} to Account ${accountId}`);
    } catch (err: any) {
      console.error(`[Deposit] Failed for Account ${accountId}:`, err?.response?.data || err.message);
    }
    await delay(50);
  }

  // 4. Create 50 Products
  console.log('\n--- Creating 50 Products ---');
  for (let i = 1; i <= 50; i++) {
    try {
      const res = await axios.post(`${GATEWAY_URL}/inventory-service/api/products`, {
        name: faker.commerce.productName(),
        price: parseFloat(faker.commerce.price({ min: 10, max: 500, dec: 2 })),
        quantity: faker.number.int({ min: 10, max: 100 })
      });
      console.log(`[Product ${i}/50] Created: ${res.data.name} ($${res.data.price}, Qty: ${res.data.quantity})`);
    } catch (err: any) {
      console.error(`[Product ${i}] Failed:`, err?.response?.data || err.message);
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
