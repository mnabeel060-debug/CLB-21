const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "admin_user_1";
const ADMIN_EMAIL = "mnabeel060@gmail.com";
const VIEWER_UID = "viewer_user_2";
const VIEWER_EMAIL = "viewer@example.com";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read transactions", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("transactions").get());
});

test("Unauthenticated user: cannot create transactions", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("transactions").doc("tx_1").set({
      type: "CASH_IN",
      date: "2026-11-07",
      amount: 50000,
      description: "Initial owner capital",
      hasReceipt: false,
      createdBy: "anon",
      createdAt: new Date(),
    })
  );
});

test("Admin (Owner): can create CASH_IN transaction", async () => {
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();

  await assertSucceeds(
    adminDb.collection("transactions").doc("tx_1").set({
      type: "CASH_IN",
      date: "2026-11-07",
      amount: 50000,
      description: "Initial owner cash in",
      hasReceipt: false,
      createdBy: ADMIN_UID,
      createdAt: new Date(),
    })
  );
});

test("Viewer: can read transactions, but cannot create CASH_OUT", async () => {
  // Pre-seed a transaction
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("transactions").doc("tx_seed").set({
      type: "CASH_IN",
      date: "2026-11-07",
      amount: 100000,
      description: "Seed cash",
      hasReceipt: false,
      createdBy: ADMIN_UID,
      createdAt: new Date(),
    });
  });

  const viewerDb = testEnv.authenticatedContext(VIEWER_UID, {
    email: VIEWER_EMAIL,
    email_verified: true,
  }).firestore();

  // Viewer can read
  await assertSucceeds(viewerDb.collection("transactions").get());
  await assertSucceeds(viewerDb.collection("transactions").doc("tx_seed").get());

  // Viewer cannot create
  await assertFails(
    viewerDb.collection("transactions").doc("tx_viewer").set({
      type: "CASH_OUT",
      date: "2026-11-07",
      amount: 5000,
      description: "Cement purchase",
      hasReceipt: false,
      createdBy: VIEWER_UID,
      createdAt: new Date(),
    })
  );

  // Viewer cannot delete
  await assertFails(viewerDb.collection("transactions").doc("tx_seed").delete());
});

test("Admin (Owner): can create receipt and Viewer can read it", async () => {
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();

  await assertSucceeds(
    adminDb.collection("receipts").doc("rcpt_1").set({
      transactionId: "tx_1",
      fileName: "cement_bill.pdf",
      mimeType: "application/pdf",
      base64Data: "JVBERi0xLjQKJ...",
      uploadedBy: ADMIN_UID,
      createdAt: new Date(),
    })
  );

  const viewerDb = testEnv.authenticatedContext(VIEWER_UID, {
    email: VIEWER_EMAIL,
    email_verified: true,
  }).firestore();

  await assertSucceeds(viewerDb.collection("receipts").doc("rcpt_1").get());
  await assertFails(viewerDb.collection("receipts").doc("rcpt_1").delete());
});
