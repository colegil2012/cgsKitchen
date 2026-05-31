
// =============================================================================
// cgsKitchen — Menu Seed Script
// =============================================================================
// Collections:
//   - categories       (id, name, sortOrder)
//   - badges           (id, label, color)
//   - option_choices   (id, label, priceDeltaCents, available, tag)
//   - option_groups    (id, label, selectionType, required, maxSelections,
//                       choiceIds, defaultChoiceId)
//   - menu_items       (id, name, description, priceCents, categoryId,
//                       badgeId, optionGroupIds, available, sortOrder)
//   - orders           (cleared)
//
//   mongosh mongodb://localhost:27017/cgskitchen cgs-kitchen-data-seed.js
// =============================================================================

const dbName = "cgskitchen";
const targetDb = db.getSiblingDB(dbName);

print(`\nSeeding "${targetDb.getName()}"...\n`);

targetDb.menu_items.drop();
targetDb.option_groups.drop();
targetDb.option_choices.drop();
targetDb.badges.drop();
targetDb.categories.drop();
targetDb.orders.drop();
targetDb.users.drop();
targetDb.carts.drop();
targetDb.addresses.drop();

//Webhook and order audit logs
targetDb.order_events.drop();
targetDb.delivery_events.drop();
targetDb.webhook_events.drop();

//Stripe tokenized payments
targetDb.payment_methods.drop();

targetDb.users.insertOne({
    _id: "admin-cole",
    email: "cole@celtechgs.com",
    passwordHash: "$2a$10$kZRhUYzWt3mFwrM1G23yb.RTZg8V7.6nfGgJwTmRpYAhLNEsT8GSm", // "ChangeMe123!"
    displayName: "Admin",
    enabled: true,
    emailVerified: true,
    roles: ["ADMIN"],
    createdAt: new Date(),
    updatedAt: new Date()
});

// ---- Categories -------------------------------------------------------------
// `_id` is the slug used in URLs / anchors; `name` is the display name.
const categories = [
    { _id: "breakfast",  name: "Breakfast",  defaultPrepTimeMinutes: 10, sortOrder: 10 },
    { _id: "sandwiches", name: "Sandwiches", defaultPrepTimeMinutes: 10, sortOrder: 20 },
    { _id: "appetizers", name: "Appetizers", defaultPrepTimeMinutes: 8,  sortOrder: 30 },
    { _id: "sides",      name: "Sides",      defaultPrepTimeMinutes: 6,  sortOrder: 40 },
    { _id: "drinks",     name: "Drinks",     defaultPrepTimeMinutes: 1,  sortOrder: 50 }
];
targetDb.categories.insertMany(categories);

// ---- Badges -----------------------------------------------------------------
// `color` is a token your CSS can map (or you can use it directly).
const badges = [
    { _id: "most-loved", label: "Most Loved", color: "gold" },
    { _id: "top-seller", label: "Top Seller", color: "grass" },
    { _id: "new",        label: "New",        color: "sea" },
    { _id: "limited",    label: "Limited",    color: "brown" }
];
targetDb.badges.insertMany(badges);

// ---- Option choices ---------------------------------------------------------
const choices = [
    // proteins
    { _id: "bacon",        label: "Bacon",          priceDeltaCents: 0,    available: true, tag: "protein" },
    { _id: "sausage",      label: "Sausage",        priceDeltaCents: 0,    available: true, tag: "protein" },
    { _id: "protein-none", label: "No meat",        priceDeltaCents: -100, available: true, tag: "protein" },

    // meats (sandwich / fries)
    { _id: "lamb",         label: "Lamb",           priceDeltaCents: 300,  available: true, tag: "meat" },
    { _id: "beef",         label: "Beef",           priceDeltaCents: 0,    available: true, tag: "meat" },
    { _id: "corned-beef",  label: "Corned beef",    priceDeltaCents: 100,  available: true, tag: "meat" },

    // cheese
    { _id: "cheddar",      label: "Cheddar",        priceDeltaCents: 0,    available: true, tag: "cheese" },
    { _id: "swiss",        label: "Swiss",          priceDeltaCents: 0,    available: true, tag: "cheese" },
    { _id: "beer-cheese",  label: "Beer cheese",    priceDeltaCents: 100,  available: true, tag: "cheese" },
    { _id: "cheese-none",  label: "No cheese",      priceDeltaCents: 0,    available: true, tag: "cheese" },

    // veggies
    { _id: "lettuce",       label: "Lettuce",           priceDeltaCents: 0,  available: true, tag: "veggie" },
    { _id: "tomato",        label: "Tomato",            priceDeltaCents: 0,  available: true, tag: "veggie" },
    { _id: "pickled-onion", label: "Pickled onion",     priceDeltaCents: 0,  available: true, tag: "veggie" },
    { _id: "mushrooms",     label: "Sautéed mushrooms", priceDeltaCents: 75, available: true, tag: "veggie" },

    // sauces
    { _id: "mint-sauce",   label: "Mint sauce",     priceDeltaCents: 0,    available: true, tag: "sauce" },
    { _id: "hp-sauce",     label: "HP sauce",       priceDeltaCents: 0,    available: true, tag: "sauce" },
    { _id: "garlic-aioli", label: "Garlic aioli",   priceDeltaCents: 50,   available: true, tag: "sauce" },
    { _id: "horseradish",  label: "Horseradish",    priceDeltaCents: 0,    available: true, tag: "sauce" },

    // fries seasoning
    { _id: "sea-salt",     label: "Sea salt",          priceDeltaCents: 0, available: true, tag: "seasoning" },
    { _id: "rosemary",     label: "Rosemary & garlic", priceDeltaCents: 0, available: true, tag: "seasoning" },
    { _id: "curry",        label: "Curry",             priceDeltaCents: 0, available: true, tag: "seasoning" },
    { _id: "salt-vinegar", label: "Salt & vinegar",    priceDeltaCents: 0, available: true, tag: "seasoning" },

    // soda flavors
    { _id: "elderflower",  label: "Elderflower", priceDeltaCents: 0, available: true, tag: "soda-flavor" },
    { _id: "ginger",       label: "Ginger",      priceDeltaCents: 0, available: true, tag: "soda-flavor" },
    { _id: "blackcurrant", label: "Blackcurrant",priceDeltaCents: 0, available: true, tag: "soda-flavor" }
];
targetDb.option_choices.insertMany(choices);

// ---- Option groups ----------------------------------------------------------
const groups = [
    { _id: "protein",     label: "Protein",   selectionType: "SINGLE", required: true,  maxSelections: 0, choiceIds: ["bacon", "sausage", "protein-none"], defaultChoiceId: "bacon" },
    { _id: "meat",        label: "Meat",      selectionType: "SINGLE", required: true,  maxSelections: 0, choiceIds: ["beef", "lamb", "corned-beef"],      defaultChoiceId: "beef" },
    { _id: "cheese",      label: "Cheese",    selectionType: "SINGLE", required: false, maxSelections: 0, choiceIds: ["cheddar", "swiss", "beer-cheese", "cheese-none"], defaultChoiceId: "cheddar" },
    { _id: "veggies",     label: "Veggies",   selectionType: "MULTI",  required: false, maxSelections: 5, choiceIds: ["lettuce", "tomato", "pickled-onion", "mushrooms"], defaultChoiceId: null },
    { _id: "sauces",      label: "Sauces",    selectionType: "MULTI",  required: false, maxSelections: 3, choiceIds: ["mint-sauce", "hp-sauce", "garlic-aioli", "horseradish"], defaultChoiceId: null },
    { _id: "seasoning",   label: "Seasoning", selectionType: "SINGLE", required: true,  maxSelections: 0, choiceIds: ["sea-salt", "rosemary", "curry", "salt-vinegar"], defaultChoiceId: "sea-salt" },
    { _id: "soda-flavor", label: "Flavor",    selectionType: "SINGLE", required: true,  maxSelections: 0, choiceIds: ["elderflower", "ginger", "blackcurrant"], defaultChoiceId: "elderflower" }
];
targetDb.option_groups.insertMany(groups);

// ---- Menu items -------------------------------------------------------------
// `categoryId` -> categories._id ; `badgeId` -> badges._id (or null).
const items = [
    {
        _id: "bkfst-boxty",
        name: "Breakfast Boxty",
        description: "Bacon, sausage, egg and cheese — any combination — on a traditional Celtic boxty.",
        priceCents: 550,
        categoryId: "breakfast",
        badgeId: "most-loved",
        optionGroupIds: ["protein", "cheese"],
        available: true,
        sortOrder: 1
    },
    {
        _id: "bkfst-irish",
        name: "Irish Breakfast Bowl",
        description: "Eggs, bacon, mushrooms, tomatoes and soda bread in a to-go bowl.",
        priceCents: 700,
        categoryId: "breakfast",
        badgeId: null,
        optionGroupIds: [],
        available: true,
        sortOrder: 2
    },
    {
        _id: "sand-shep",
        name: "Shepherd's Sandwich",
        description: "Slow-roasted meat on a fresh-baked soda bread bun.",
        priceCents: 950,
        categoryId: "sandwiches",
        badgeId: "top-seller",
        optionGroupIds: ["meat", "cheese", "veggies", "sauces"],
        defaultsByGroupId: {
            veggies: ["lettuce", "tomato", "pickled-onion", "mushrooms"],
            sauces: ["horseradish"]
        },
        available: true,
        sortOrder: 10
    },
    {
        _id: "shep-fries",
        name: "Shepherd's Fries",
        description: "Wedge potatoes with ground meat, cheese, mash and gravy.",
        priceCents: 600,
        categoryId: "appetizers",
        badgeId: null,
        optionGroupIds: ["meat", "cheese", "veggies", "sauces"],
        defaultsByGroupId: {
            veggies: ["pickled-onion", "mushrooms"],
            sauces: ["mint-sauce"],
            cheese: ["beer-cheese"]
        },
        available: true,
        sortOrder: 20
    },
    {
        _id: "welsh-rbit",
        name: "Welsh Rarebit",
        description: "House-made beer cheese over soda bread.",
        priceCents: 400,
        categoryId: "appetizers",
        badgeId: "top-seller",
        optionGroupIds: [],
        available: true,
        sortOrder: 21
    },
    {
        _id: "fish-cakes",
        name: "Fish Cakes",
        description: "Potatoes, fish, onions, parsley and egg fried into a patty with tartar sauce and lemon.",
        priceCents: 850,
        categoryId: "appetizers",
        badgeId: null,
        optionGroupIds: [],
        available: true,
        sortOrder: 22
    },
    {
        _id: "side-colcannon",
        name: "Colcannon",
        description: "Irish mashed potatoes with butter, milk, cabbage and spring onion.",
        priceCents: 500,
        categoryId: "sides",
        badgeId: null,
        optionGroupIds: [],
        available: true,
        sortOrder: 30
    },
    {
        _id: "side-chips",
        name: "Chips",
        description: "House-made chips — pick your seasoning.",
        priceCents: 300,
        categoryId: "sides",
        badgeId: null,
        optionGroupIds: ["seasoning"],
        available: true,
        sortOrder: 31
    },
    {
        _id: "drink-shillelagh",
        name: "Shillelagh",
        description: "Peach and lemon juice, powdered sugar and garnish.",
        priceCents: 400,
        categoryId: "drinks",
        badgeId: null,
        optionGroupIds: [],
        available: true,
        sortOrder: 40
    },
    {
        _id: "drink-mint-tea",
        name: "Mint Tea",
        description: "Freshly brewed mint tea, sweetened with stevia.",
        priceCents: 400,
        categoryId: "drinks",
        badgeId: null,
        optionGroupIds: [],
        available: true,
        sortOrder: 41
    },
    {
        _id: "drink-soda",
        name: "Soda",
        description: "House-made soda — pick your flavor.",
        priceCents: 300,
        categoryId: "drinks",
        badgeId: null,
        optionGroupIds: ["soda-flavor"],
        available: true,
        sortOrder: 42
    }
];
targetDb.menu_items.insertMany(items);

print(`\nDone! ${categories.length} categories, ${badges.length} badges, ${choices.length} choices, ${groups.length} groups, ${items.length} items.\n`);