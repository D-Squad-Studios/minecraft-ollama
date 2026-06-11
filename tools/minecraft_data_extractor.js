#!/usr/bin/env node

const fs = require('fs');
const path = require("path")
const outputDir = path.join(__dirname, 'minecraft_data_output');

// create directory if DNE
if (!fs.existsSync(outputDir)) {
    fs.mkdirSync(outputDir, { recursive: true });
    console.log(`Created directory: ${outputDir}`);
}

try {
    const mcData = require('minecraft-data');

    // specify minecraft version
    const version = '1.21.8';
    const mc = mcData(version);

    console.log(`Loading Minecraft data for version ${version}...`);
    console.log('='.repeat(60));

    // helper function get item name from ID
    function getItemName(itemId) {
        if (itemId === null || itemId === undefined) return null;
        const item = mc.items[itemId];
        return item ? item.name : `Unknown(${itemId})`;
    }

    // crafting recipes
    console.log('\nExtracting crafting recipes...');
    const uniqueRecipes = new Map(); // Use a Map to help reduce duplicates

    if (mc.recipes) {
        Object.entries(mc.recipes).forEach(([resultItemId, recipeList]) => {
            const resultItemName = getItemName(parseInt(resultItemId));

            if (Array.isArray(recipeList)) {
                recipeList.forEach((recipe) => {
                    const recipeData = {
                        resultingItem: {
                            item: resultItemName,
                            itemCount: recipe.result?.count || 1
                        }
                    };

                    // shaped recipes
                    if (recipe.inShape) {
                        recipeData.type = 'shaped';
                        recipeData.pattern = recipe.inShape.map(row =>
                            row.map(itemId => {
                                let item = getItemName(itemId);
                                if (!item) return "empty";

                                // generalize recipes that use wood
                                if (item.endsWith('_planks')) return 'planks';
                                if (item.endsWith('_log')) return 'logs';
                                if (item.endsWith('_wood')) return 'wood';
                                return item;
                            })
                        );
                    }
                    // shapeless recipes
                    else if (recipe.ingredients) {
                        recipeData.type = 'shapeless';
                        recipeData.ingredients = recipe.ingredients.map(itemId => {
                            let item = getItemName(itemId);
                            if (item.endsWith('_planks')) return 'planks';
                            if (item.endsWith('_log')) return 'logs';
                            if (item.endsWith('_wood')) return 'wood';
                            return item;
                        });
                    }
                    else {
                        recipeData.type = 'other';
                    }

                    // create a unique key for de-duplication
                    const recipeKey = `${recipeData.resultingItem.item}_${JSON.stringify(recipeData.pattern || recipeData.ingredients)}`;

                    if (!uniqueRecipes.has(recipeKey)) {
                        uniqueRecipes.set(recipeKey, recipeData);
                    }
                });
            }
        });
    }

    const craftingRecipes = Array.from(uniqueRecipes.values());
    console.log(`Found ${craftingRecipes.length} unique generalized crafting recipes`);

    // status effects
    console.log('\nExtracting status effects...');
    const effects = [];

    if (mc.effects) {
        Object.values(mc.effects).forEach(effect => {
            effects.push({
                name: effect.name,
                displayName: effect.displayName,
                type: effect.type || 'unknown'
            });
        });
    }

    console.log(`Found ${effects.length} status effects`);

    // in game items
    console.log('\nExtracting items...');
    const items = [];

    if (mc.items) {
        Object.values(mc.items).forEach(item => {
            items.push({
                name: item.name,
                displayName: item.displayName,
                stackSize: item.stackSize
            });
        });
    }

    console.log(`Found ${items.length} items`);

    // foods
    console.log('\nExtracting foods...');
    const foods = [];

    if (mc.foods) {
        Object.values(mc.foods).forEach(food => {
            const foodData = {
                name: getItemName(food.id),
                hungerBarsRestored: food.foodPoints * .5,
                saturation: food.saturation
            };
            foods.push(foodData);
        });
    }

    console.log(`Found ${foods.length} food items`);

    // brewing ingredients
    console.log('\nExtracting brewing ingredients...');
    const brewingIngredients = [];

    if (mc.items) {
        Object.values(mc.items).forEach(item => {
            // common brewing ingredients
            const brewingKeywords = [
                'nether_wart', 'glowstone', 'redstone', 'fermented_spider_eye',
                'magma_cream', 'sugar', 'glistering_melon', 'blaze_powder',
                'ghast_tear', 'dragon_breath', 'phantom_membrane', 'gunpowder'
            ];

            if (brewingKeywords.some(keyword => item.name.toLowerCase().includes(keyword))) {
                brewingIngredients.push({
                    name: item.name,
                    displayName: item.displayName
                });
            }
        });
    }

    console.log(`Found ${brewingIngredients.length} brewing ingredients`);

    // biomes
    console.log('\nExtracting biomes...');
    const biomes = [];

    if (mc.biomes) {
        const biomeList = Array.isArray(mc.biomes) ? mc.biomes : Object.values(mc.biomes);

        biomeList.forEach(biome => {
            biomes.push({
                name: biome.name,
                displayName: biome.displayName,
                category: biome.category || 'unknown',
                dimension: biome.dimension || 'unknown'
            });
        });
    }
    console.log(`Found ${biomes.length} biomes`);

    // enchantments
    console.log('\nExtracting enchantments...');
    const enchantments = [];

    if (mc.enchantments) {
        const enchantmentList = Array.isArray(mc.enchantments) ? mc.enchantments : Object.values(mc.enchantments);

        enchantmentList.forEach(enchant => {
            enchantments.push({
                name: enchant.name,
                displayName: enchant.displayName,
                maxLevel: enchant.maxLevel,
                category: enchant.category,
                exclude: enchant.exclude || [] // enchantments this cannot be combined with
            });
        });
    }
    console.log(`Found ${enchantments.length} enchantments`);

    // Extract Entities
    console.log('\nExtracting entities...');
    const entities = [];

    if (mc.entities) {
        const entityList = Array.isArray(mc.entities) ? mc.entities : Object.values(mc.entities);

        entityList.forEach(entity => {
            entities.push({
                name: entity.name,
                displayName: entity.displayName,
                type: entity.type, // e.g., 'mob', 'player', 'projectile'
                category: entity.category,
                width: entity.width,
                height: entity.height
            });
        });
    }
    console.log(`Found ${entities.length} entities`);


    // Save to JSON files
    console.log('\n' + '='.repeat(60));
    console.log('Saving data to JSON files...');

    function saveData(filename, data) {
        fs.writeFileSync(
            path.join(outputDir, filename),
            JSON.stringify(data, null, 2)
        );
        console.log(`✓ Saved ${filename}`);
    }

    saveData('crafting_recipes.json', craftingRecipes);
    saveData('effects.json', effects)
    saveData('items.json', items)
    saveData('foods.json', foods)
    saveData('brewing_ingredients.json', brewingIngredients)
    saveData('biomes.json', biomes)
    saveData('enchantments.json', enchantments)
    saveData('entities.json', entities)

    console.log('\n' + '='.repeat(60));
    console.log('Data extraction complete!');
    console.log('\n📊 Summary:');
    console.log(`  - Crafting Recipes: ${craftingRecipes.length}`);
    console.log(`  - Status Effects: ${effects.length}`);
    console.log(`  - Items: ${items.length}`);
    console.log(`  - Foods: ${foods.length}`);
    console.log(`  - Brewing Ingredients: ${brewingIngredients.length}`);
    console.log(`  - Biomes: ${biomes.length}`);
    console.log(`  - Enchantments: ${enchantments.length}`);
    console.log(`  - Entities: ${entities.length}`);
    console.log('='.repeat(60));

} catch (error) {
    if (error.code === 'MODULE_NOT_FOUND') {
        console.error('Error: minecraft-data package not found!');
        console.error('\nPlease install it first:');
        console.error('  npm install minecraft-data');
        console.error('\nThen run this script again:');
        console.error('  node minecraft_data_extractor.js');
    } else {
        console.error('Error:', error.message);
        console.error(error.stack);
    }
    process.exit(1);
}