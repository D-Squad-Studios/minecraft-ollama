# Minecraft Data Collection Scripts

This repository contains two JavaScript scripts for extracting and organizing Minecraft game data.

## Files

- `brewing_recipes.js` - Extracts brewing recipe data from Minecraft
- `minecraft_data_extractor.js` - Extracts general Minecraft data

## Prerequisites

- **Node.js** installed on your system
- An IDE (VS Code recommended)

## Setup Instructions

### 1. Install Dependencies

Open your terminal in the project directory and run:

```bash
npm install minecraft-data
```

This will install the required `minecraft-data` module and its dependencies.

### 2. Run the Scripts

Once the modules are installed, you can run either script using Node.js:

```bash
node tools/brewing_recipes.js
```

or

```bash
node tools/minecraft_data_extractor.js
```

## Output

All extracted data will be saved to a folder called `minecraft_data_output` in your project directory. This folder will be created automatically when you run the scripts.

## Notes

- Make sure you're in the correct directory when running the commands
- The scripts will create the output folder if it doesn't already exist
- You may need to run the scripts separately depending on what data you need to collect

## Troubleshooting

If you encounter any errors:
- Verify that Node.js is properly installed by running `node --version`
- Ensure the `minecraft-data` package installed correctly
- Check that you're running the commands from the project root directory
