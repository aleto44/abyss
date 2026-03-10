# Abyss Game UI and Inventory Specification

This document provides a detailed specification for the UI, GUI, and inventory systems in the Abyss-inspired voxel game. It focuses exclusively on conceptual design, emphasizing minimalism, immersion, and integration with the game world. This is intended as input for an AI agent to assist in development, prototyping, or code generation. Do not implement code; use this as a descriptive blueprint.

## Overview
- **Minimalist GUI Philosophy**: The UI should be as unobtrusive as possible, with minimal on-screen elements to maintain immersion in the voxel abyss world. Avoid traditional HUD overlays; instead, integrate information and interactions directly into the game environment, player model, or contextual pop-ups.
- **Immersion Focus**: UI elements should feel like extensions of the game world rather than separate interfaces. For example, inventory management ties into physical representations on the player character.
- **Dynamic and Conditional Design**: UI visibility and functionality depend on player possessions, actions, and context. This encourages exploration and knowledge-based progression, aligning with the game's core mechanics.
- **Perspective**: Assume a first-person or third-person view where the player model is visible (e.g., in third-person or via mirrors/reflections in the environment).
- **Accessibility Considerations**: Provide subtle options for color-blind modes or scaling, but keep them optional and non-intrusive.

## Inventory System
- **Integrated Inventory Concept**: The inventory is not a traditional menu or grid; it is visualized and interacted with as physical items attached to or carried by the player character. This makes inventory management feel like part of the physical world, enhancing realism and tying into voxel interactions.
- **Item Representations**: Each storable item has a dedicated "inventory version" model:
    - This is a simplified 3D representation of the item with a hitbox for interaction (e.g., clicking or dragging in-game).
    - Items are displayed attached to the player's bag or body, visible in the world (e.g., tools strapped to belts, artifacts in pouches).
    - Hitboxes allow for direct manipulation: Players can select, rearrange, or drop items by interacting with their physical models (e.g., raycasting from the cursor to the item's hitbox).
- **Non-Grid Management**: Inventory uses a volume/weight-based system (e.g., total capacity limits). No visible slots or grids; capacity is inferred from the dynamic bag size and visible item clutter.
- **Interaction Mechanics**:
    - Opening Inventory: Triggered contextually (e.g., hold a key to "inspect bag"), revealing items as manipulable objects around the player model (e.g., floating or laid out in a semi-circle for selection).
    - Adding/Removing Items: Physically "place" items into the bag via drag-and-drop or auto-attachment. Over-encumbrance visually strains the bag (e.g., bulging or sagging).
    - Stacking: Similar items merge visually (e.g., multiple herbs in one bundle model).

## Player Model Integration
- **Dynamic Bag Display**: The player's backpack is a core visual element on the character model:
    - Size and appearance change based on inventory capacity and contents (e.g., small pouch for light loads, massive rucksack for heavy encumbrance).
    - Procedural generation: Bag model scales or morphs (e.g., add straps/modules) as players craft/upgrade bags.
- **Protruding Items**: Oversized or elongated items visibly stick out of the bag:
    - Examples: Long sticks or poles protrude from the top/sides; large artifacts bulge pockets.
    - This affects gameplay: Protruding items might snag on environment (e.g., increased noise attracting creatures) or provide benefits (e.g., a protruding weapon for quick draw).
    - Visual Fidelity: Use attachments/nodes on the player model (e.g., skeletal rigging to sway with movement).
- **Hooking System**: Players can attach items or sub-bags to the main bag using "hook" items (e.g., carabiners or straps):
    - Hooked Items: Smaller artifacts or tools hang externally (e.g., a lantern on a side hook).
    - Sub-Bags: Additional pouches or satchels hooked on, expanding capacity visually (e.g., a belt of small bags).
    - Display: All hooked elements appear on the player model in real-time, with physics simulation (e.g., swinging during movement).
    - Functional Effects: Hooked items provide active benefits:
        - A hooked light orb emits actual light from its position on the model, illuminating the surroundings dynamically.
        - Hooked weapons/tools allow quick access without full inventory interaction.
    - Risks: Hooked items are more vulnerable (e.g., can be damaged or stolen by creatures).
- **Character Model Customization**: The player model updates in real-time to reflect inventory state, making the character a "living UI" (e.g., heavier loads slow animations subtly).

## Conditional UI Elements
- **Possession-Based Visibility**: UI elements only appear if the player possesses enabling items, promoting discovery and knowledge progression.
    - Example: A "depth meter" item unlocks a subtle on-screen depth indicator (e.g., a floating numeral or gauge integrated into the environment view).
    - Other Examples:
        - Compass artifact: Reveals a directional HUD arrow or world-aligned icon.
        - Map relic: Unlocks a mini-map overlay, but only for explored areas.
        - Health monitor device: Displays a non-intrusive health bar (e.g., as a glowing aura on the player model).
        - Knowledge journal: Opens a contextual book-like interface only if the player has collected observation entries.
- **Contextual Activation**: Elements fade in/out based on relevance (e.g., ascent curse warnings only during upward movement).
- **Minimalist Presentation**: When shown, UI elements are diegetic (part of the world):
    - No floating windows; use holographic projections from artifacts or etched displays on items.
    - Audio/Visual Cues: Subtle sounds or particle effects signal UI activation (e.g., a chime when a new element unlocks).
- **Multiplayer Considerations**: In co-op, shared UI elements (e.g., group health) appear only if a player in the group possesses the enabling item, visible to all.

This specification ensures the UI and inventory enhance immersion, making them feel like natural extensions of the abyss exploration rather than detached interfaces.
