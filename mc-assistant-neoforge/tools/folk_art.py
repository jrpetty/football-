#!/usr/bin/env python3
"""The village folk: their bodies, their faces and the clothes of every trade.

One file holds all of it, so the shape of a folk and the paint on it can never
drift apart:

  * PARTS    the model — every box, where it hangs and which trade wears it.
             `java` writes it into FolkModel.createBodyLayer().
  * skins    faces, hair, skin and the plain clothes under everything
             (textures/entity/folk/skin_N.png): who a folk is.
  * outfits  one picture per trade (textures/entity/folk/<trade>.png) laid over
             the skin: what a folk does. Hats, aprons, packs and shields are
             boxes of their own, painted only on their trade's picture.
  * glow     the light a miner carries (textures/entity/folk/miner_glow.png), the
             cave dweller's helm lamp (cavedweller_glow.png) and the emerald trader's
             lantern at its pack (emerald_glow.png).

    python3 tools/folk_art.py            # write the pictures and the Java
    python3 tools/folk_art.py --check    # only say what would change

No dependencies: the PNGs are written by hand (zlib + struct), like
generate_uniforms.py. Every picture is 128 x 128, and every box is laid out the
way Minecraft lays out a box's six faces:

          top    bottom
    right  front  left  back

with "right" and "left" the folk's own, and every face drawn as seen from
outside it.
"""
import os
import re
import struct
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
TEX_DIR = os.path.join(ROOT, "src/main/resources/assets/mc_assistant/textures/entity/folk")
JAVA = os.path.join(ROOT, "src/main/java/com/jrpetty/mcassistant/client/FolkModel.java")
LOOKS_JAVA = os.path.join(ROOT, "src/main/java/com/jrpetty/mcassistant/client/FolkLooks.java")

SIZE = 128

# --------------------------------------------------------------------- the model
#
# (name, parent, pivot, rotation (radians), cubes, worn-by)
# cube: (u, v, x, y, z, w, h, d, inflate)
# worn-by: "all", "beard", or a trade name (as in TRADES).
#
# Model space is Minecraft's: y grows DOWN, the face looks toward -z, and -x is
# the folk's own right. The feet stand on y = 24.

TRADES = ["none", "farmer", "lumberjack", "miner", "rancher", "guard",
          "smelter", "fisher", "storekeeper", "hauler",
          "blacksmith", "tailor", "beekeeper", "brewer", "enchanter", "cook", "shopkeeper", "scout", "hunter",
          # [caves] Not in StationTask's order: the cave dweller's own (FolkModel.outfit picks it for CAVE).
          "cavedweller",
          # [fletcher] [golems] The fletcher's and the golem keeper's own (FolkModel.outfit picks them).
          "fletcher", "golemkeeper",
          # [fireworks] The fireworks maker's own (FolkModel.outfit picks it for FIREWORKS).
          "fireworks",
          # [cartographer] Likewise the cartographer's (FolkModel.outfit picks it for CARTOGRAPHER).
          "cartographer",
          # [emerald] The emerald trader's own (FolkModel.outfit picks it for EMERALD).
          "emerald",
          # [nether] And the Nether runner's (FolkModel.outfit picks it for NETHER).
          "netherrunner"]

PARTS = [
    # The body every folk has: a villager's head and nose, a coat over a body,
    # and arms and legs of their own.
    ("head", None, (0, 0, 0), (0, 0, 0), [(0, 0, -4, -10, -4, 8, 10, 8, 0)], "all"),
    ("hair", "head", (0, 0, 0), (0, 0, 0), [(32, 0, -4, -10, -4, 8, 10, 8, 0.5)], "all"),
    ("nose", "head", (0, -2, 0), (0, 0, 0), [(24, 0, -1, -1, -6, 2, 4, 2, 0)], "all"),
    ("beard", "head", (0, 0, 0), (0, 0, 0), [(44, 50, -3, -3, -5, 6, 5, 1, 0)], "beard"),
    ("body", None, (0, 0, 0), (0, 0, 0), [(0, 18, -4, 0, -3, 8, 12, 6, 0)], "all"),
    ("coat", "body", (0, 0, 0), (0, 0, 0), [(0, 36, -4, 0, -3, 8, 18, 6, 0.5)], "all"),
    ("right_arm", None, (-5, 2, 0), (0, 0, 0), [(28, 18, -3, -2, -2, 4, 12, 4, 0)], "all"),
    ("left_arm", None, (5, 2, 0), (0, 0, 0), [(44, 18, -1, -2, -2, 4, 12, 4, 0)], "all"),
    ("right_leg", None, (-2, 12, 0), (0, 0, 0), [(28, 34, -2, 0, -2, 4, 12, 4, 0)], "all"),
    ("left_leg", None, (2, 12, 0), (0, 0, 0), [(44, 34, -2, 0, -2, 4, 12, 4, 0)], "all"),

    # Newcomer: a traveller's cloak, the same cloth as its hood.
    ("none_cloak", "body", (0, 0, 0), (0, 0, 0), [(64, 0, -4.5, -0.6, 3.6, 9, 14, 1, 0)], "none"),

    # Farmer: a broad straw hat and a seed pouch.
    ("farmer_crown", "head", (0, 0, 0), (0, 0, 0), [(64, 17, -4, -12, -4, 8, 4, 8, 0.6)], "farmer"),
    ("farmer_brim", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -8, -8, -8, 16, 1, 16, 0)], "farmer"),
    ("farmer_pouch", "body", (0, 0, 0), (0, 0, 0), [(96, 17, 1, 9.5, -4.5, 3, 3, 1, 0)], "farmer"),

    # Lumberjack: a knitted cap, a beard, and the day's logs on a frame on its back.
    ("lumberjack_cap", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -11, -4, 8, 3, 8, 0.6)], "lumberjack"),
    ("lumberjack_cuff", "head", (0, 0, 0), (0, 0, 0), [(64, 11, -4, -8, -4, 8, 1, 8, 0.85)], "lumberjack"),
    ("lumberjack_bobble", "head", (0, 0, 0), (0, 0, 0), [(96, 0, -1, -13.5, -1, 2, 2, 2, 0)], "lumberjack"),
    ("lumberjack_rack", "body", (0, 0, 0), (0, 0, 0), [(64, 20, -4, 1, 3.6, 8, 10, 1, 0)], "lumberjack"),
    ("lumberjack_log_top", "body", (0, 0, 0), (0, 0, 0), [(64, 31, -5, 1.5, 4.6, 10, 3, 3, 0)], "lumberjack"),
    ("lumberjack_log_low", "body", (0, 0, 0), (0, 0, 0), [(90, 31, -5, 5, 4.6, 10, 3, 3, 0)], "lumberjack"),

    # Miner: a hard hat with a lamp, and a lantern on its belt.
    ("miner_shell", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -11, -4, 8, 4, 8, 0.6)], "miner"),
    ("miner_brim", "head", (0, 0, 0), (0, 0, 0), [(64, 12, -5, -7, -5, 10, 1, 10, 0)], "miner"),
    ("miner_lamp", "head", (0, 0, 0), (0, 0, 0), [(96, 0, -1.5, -10, -5.6, 3, 2, 1, 0)], "miner"),
    ("miner_lantern", "body", (0, 0, 0), (0, 0, 0), [(104, 0, 1, 9.5, -5.5, 3, 4, 2, 0)], "miner"),

    # Rancher: a wide hat with its brim turned up, a wool shawl and a coil of rope.
    ("rancher_crown", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -12, -4, 8, 3, 8, 0.6)], "rancher"),
    ("rancher_brim", "head", (0, 0, 0), (0, 0, 0), [(64, 11, -7, -9, -7, 14, 1, 14, 0)], "rancher"),
    ("rancher_curl_right", "head", (0, 0, 0), (0, 0, 0), [(64, 26, -8, -10, -7, 1, 1, 14, 0)], "rancher"),
    ("rancher_curl_left", "head", (0, 0, 0), (0, 0, 0), [(64, 26, 7, -10, -7, 1, 1, 14, 0)], "rancher"),
    ("rancher_shawl", "body", (0, 0, 0), (0, 0, 0), [(64, 41, -5, -0.8, -4, 10, 5, 8, 0)], "rancher"),
    ("rancher_rope", "body", (0, 0, 0), (0, 0, 0), [(100, 41, -4, 9, -4.5, 3, 3, 1, 0)], "rancher"),

    # Guard: a kettle hat, a shield on its back and a scabbard at its hip.
    ("guard_shell", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -11, -4, 8, 3, 8, 0.6)], "guard"),
    ("guard_brim", "head", (0, 0, 0), (0, 0, 0), [(64, 11, -6, -8, -6, 12, 1, 12, 0)], "guard"),
    ("guard_shield", "body", (0, 0, 0), (0, 0, 0), [(64, 24, -4, 1, 3.6, 8, 10, 1, 0)], "guard"),
    ("guard_scabbard", "body", (4.7, 9, 3), (0.28, 0, -0.08), [(82, 24, -0.5, 0, -1, 1, 8, 2, 0)], "guard"),

    # Smelter: a heavy leather apron and goggles pushed up on its forehead.
    ("smelter_apron", "body", (0, 0, 0), (0, 0, 0), [(64, 0, -4, 1.5, -4.5, 8, 15, 1, 0)], "smelter"),
    ("smelter_band", "head", (0, 0, 0), (0, 0, 0), [(82, 0, -4, -9, -4, 8, 2, 8, 0.6)], "smelter"),
    ("smelter_lens_right", "head", (0, 0, 0), (0, 0, 0), [(114, 0, -3, -9, -5.6, 2, 2, 1, 0)], "smelter"),
    ("smelter_lens_left", "head", (0, 0, 0), (0, 0, 0), [(114, 0, 1, -9, -5.6, 2, 2, 1, 0)], "smelter"),

    # Fisher: a sou'wester with a long back brim, and a creel on its back.
    ("fisher_crown", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -11, -4, 8, 3, 8, 0.6)], "fisher"),
    ("fisher_brim", "head", (0, 0, 0), (0, 0, 0), [(64, 11, -5, -8, -5, 10, 1, 10, 0)], "fisher"),
    ("fisher_flap", "head", (0, -8, 4.6), (-0.5, 0, 0), [(64, 22, -5, 0, 0, 10, 1, 4, 0)], "fisher"),
    ("fisher_creel", "body", (0, 0, 0), (0, 0, 0), [(64, 27, -3, 5, 3.6, 6, 5, 3, 0)], "fisher"),
    ("fisher_lid", "body", (0, 0, 0), (0, 0, 0), [(82, 27, -3.5, 4, 3.4, 7, 1, 4, 0)], "fisher"),

    # Storekeeper: a derby, a ledger at its belt and a quill behind its ear.
    ("storekeeper_crown", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -12, -4, 8, 3, 8, 0.6)], "storekeeper"),
    ("storekeeper_brim", "head", (0, 0, 0), (0, 0, 0), [(64, 11, -5, -9, -5, 10, 1, 10, 0)], "storekeeper"),
    ("storekeeper_ledger", "body", (0, 0, 0), (0, 0, 0), [(104, 11, -4.5, 9, -4.5, 3, 4, 1, 0)], "storekeeper"),
    ("storekeeper_quill", "head", (4.4, -5, 0.5), (-0.35, 0, 0.12), [(112, 11, -0.5, -4, -0.5, 1, 5, 1, 0)], "storekeeper"),

    # Hauler: a flat cap, and a pack with a bedroll and a pan.
    ("hauler_cap", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -10, -4, 8, 2, 8, 0.6)], "hauler"),
    ("hauler_visor", "head", (0, 0, 0), (0, 0, 0), [(96, 0, -3.5, -8, -7, 7, 1, 3, 0)], "hauler"),
    ("hauler_pack", "body", (0, 0, 0), (0, 0, 0), [(64, 10, -4, 0.5, 3.6, 8, 10, 4, 0)], "hauler"),
    ("hauler_roll", "body", (0, 0, 0), (0, 0, 0), [(88, 10, -5, -2.5, 4.1, 10, 3, 3, 0)], "hauler"),
    ("hauler_pan", "body", (0, 0, 0), (0, 0, 0), [(114, 10, 4, 3, 5, 1, 4, 3, 0)], "hauler"),

    # Blacksmith: a long scorched apron, a headscarf knotted behind, a hammer at its hip.
    ("blacksmith_apron", "body", (0, 0, 0), (0, 0, 0), [(64, 0, -4, 1.5, -4.5, 8, 15, 1, 0)], "blacksmith"),
    ("blacksmith_scarf", "head", (0, 0, 0), (0, 0, 0), [(64, 17, -4, -10, -4, 8, 3, 8, 0.6)], "blacksmith"),
    ("blacksmith_knot", "head", (0, 0, 0), (0, 0, 0), [(96, 17, -1, -9, 4.4, 2, 2, 1, 0)], "blacksmith"),
    ("blacksmith_hammer", "body", (0, 0, 0), (0, 0, 0),
     [(100, 0, 4.2, 8, -1, 1, 6, 1, 0), (104, 0, 3.7, 6.5, -2, 2, 2, 3, 0)], "blacksmith"),

    # Tailor: a beret, a tape measure round its neck, a spool of thread at its belt.
    ("tailor_beret", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4.5, -11, -4.5, 9, 2, 9, 0)], "tailor"),
    ("tailor_tape", "body", (0, 0, 0), (0, 0, 0), [(64, 12, -4.5, -0.6, -3.5, 9, 1, 7, 0)], "tailor"),
    ("tailor_spool", "body", (0, 0, 0), (0, 0, 0), [(100, 0, 1, 9.5, -4.5, 2, 3, 1, 0)], "tailor"),

    # Beekeeper: a wide hat with a veil of netting to the shoulders, and a smoker at its belt.
    ("beekeeper_crown", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -12, -4, 8, 3, 8, 0.6)], "beekeeper"),
    ("beekeeper_brim", "head", (0, 0, 0), (0, 0, 0), [(64, 11, -7, -9, -7, 14, 1, 14, 0)], "beekeeper"),
    ("beekeeper_veil", "head", (0, 0, 0), (0, 0, 0), [(64, 26, -6, -8.5, -6, 12, 9, 12, 0)], "beekeeper"),
    ("beekeeper_smoker", "body", (0, 0, 0), (0, 0, 0), [(104, 0, 1.5, 8.5, -5, 2, 4, 2, 0)], "beekeeper"),

    # Brewer: a soft cap, a stained apron and a belt of little vials.
    ("brewer_cap", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -11, -4, 8, 2, 8, 0.6)], "brewer"),
    ("brewer_apron", "body", (0, 0, 0), (0, 0, 0), [(64, 16, -4, 2, -4.5, 8, 13, 1, 0)], "brewer"),
    ("brewer_vial_a", "body", (0, 0, 0), (0, 0, 0), [(100, 12, -3, 7.5, -5.6, 1, 2, 1, 0)], "brewer"),
    ("brewer_vial_b", "body", (0, 0, 0), (0, 0, 0), [(104, 12, -1, 7.5, -5.6, 1, 2, 1, 0)], "brewer"),
    ("brewer_vial_c", "body", (0, 0, 0), (0, 0, 0), [(108, 12, 1, 7.5, -5.6, 1, 2, 1, 0)], "brewer"),

    # Enchanter: a tall pointed hat and a book of spells at its belt.
    ("enchanter_hat_base", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -5, -11, -5, 10, 2, 10, 0.3)], "enchanter"),
    ("enchanter_hat_mid", "head", (0, 0, 0), (0, 0, 0), [(64, 12, -3, -14, -3, 6, 3, 6, 0)], "enchanter"),
    ("enchanter_hat_tip", "head", (0, 0, 0), (0, 0, 0), [(88, 12, -1.5, -17, -1.5, 3, 3, 3, 0)], "enchanter"),
    ("enchanter_book", "body", (0, 0, 0), (0, 0, 0), [(104, 0, -4.5, 9, -4.5, 3, 4, 1, 0)], "enchanter"),

    # Cook: a tall white toque, an apron, and a wooden spoon tucked in its belt.
    ("cook_band", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -11, -4, 8, 2, 8, 0.6)], "cook"),
    ("cook_puff", "head", (0, 0, 0), (0, 0, 0), [(64, 12, -4.5, -15, -4.5, 9, 4, 9, 0)], "cook"),
    ("cook_apron", "body", (0, 0, 0), (0, 0, 0), [(64, 26, -4, 2, -4.5, 8, 13, 1, 0)], "cook"),
    ("cook_spoon", "body", (0, 0, 0), (0, 0, 0), [(100, 0, 3.5, 7.5, -4.5, 1, 5, 1, 0)], "cook"),

    # Shopkeeper: a cap with a green visor, a striped apron and a coin purse.
    ("shopkeeper_cap", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -10, -4, 8, 2, 8, 0.6)], "shopkeeper"),
    ("shopkeeper_visor", "head", (0, 0, 0), (0, 0, 0), [(96, 0, -3.5, -8.5, -7, 7, 1, 3, 0)], "shopkeeper"),
    ("shopkeeper_apron", "body", (0, 0, 0), (0, 0, 0), [(64, 12, -4, 3, -4.5, 8, 12, 1, 0)], "shopkeeper"),
    ("shopkeeper_pouch", "body", (0, 0, 0), (0, 0, 0), [(100, 12, 1, 9, -5, 3, 3, 1, 0)], "shopkeeper"),

    # Scout: a ranger's hood and a cape to the knee, a map case at its hip, a brass
    # spyglass at its belt and a red feather in its hood.
    ("scout_hood", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -10, -4, 8, 10, 8, 0.75)], "scout"),
    ("scout_feather", "head", (4.2, -9, 1), (0, 0, 0.45), [(100, 12, -0.5, -4, -0.5, 1, 4, 1, 0)], "scout"),
    ("scout_cape", "body", (0, 0, 0), (0, 0, 0), [(64, 20, -4.5, 0, 3.3, 9, 16, 1, 0)], "scout"),
    ("scout_satchel", "body", (0, 0, 0), (0, 0, 0), [(100, 0, 3.8, 7, -2, 2, 5, 4, 0)], "scout"),
    ("scout_spyglass", "body", (0, 0, 0), (0, 0, 0), [(114, 0, -5, 7, -1, 1, 4, 1, 0)], "scout"),

    # Hunter: a mottled hood, a fur mantle round its shoulders, a quiver of arrows slung
    # across its back and a skinning knife at its hip.
    ("hunter_hood", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -10, -4, 8, 10, 8, 0.75)], "hunter"),
    ("hunter_mantle", "body", (0, 0, 0), (0, 0, 0), [(64, 20, -4.5, -0.5, -3.5, 9, 4, 7, 0.3)], "hunter"),
    ("hunter_quiver", "body", (0, 2, 3.4), (0, 0, 0.35), [(100, 0, -1.5, -1, 0, 3, 10, 2, 0)], "hunter"),
    ("hunter_fletch", "body", (0, 2, 3.4), (0, 0, 0.35), [(112, 0, -1, -4, 0.5, 2, 3, 1, 0)], "hunter"),
    ("hunter_knife", "body", (0, 0, 0), (0, 0, 0), [(100, 14, -5, 8, -1, 1, 4, 1, 0)], "hunter"),

    # [caves] Cave dweller: a dented steel delver's helm with a big brass lamp strapped to its front (the lamp
    # stays on over an iron helmet, and glows), a long oilskin coat, a coil of rope at its hip and a spare pick
    # slung across its back.
    ("cavedweller_shell", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -11, -4, 8, 4, 8, 0.6)], "cavedweller"),
    ("cavedweller_brim", "head", (0, 0, 0), (0, 0, 0), [(64, 12, -5, -7, -5, 10, 1, 10, 0)], "cavedweller"),
    ("cavedweller_lamp", "head", (0, 0, 0), (0, 0, 0), [(104, 0, -2, -10.5, -6.8, 4, 3, 2, 0)], "cavedweller"),
    ("cavedweller_haft", "body", (0, 6, 4.4), (0, 0, 0.7), [(64, 24, -0.5, -6, 0, 1, 12, 1, 0)], "cavedweller"),
    ("cavedweller_pickhead", "body", (0, 6, 4.4), (0, 0, 0.7), [(68, 24, -3.5, -7, 0, 7, 1, 1, 0)], "cavedweller"),
    ("cavedweller_rope", "body", (0, 0, 0), (0, 0, 0), [(84, 24, -6.2, 7, -2, 2, 4, 4, 0)], "cavedweller"),

    # [fletcher] Fletcher: a green felt cap with a peak and a long goose feather in its band, a leather bib apron over
    # a green tunic, and a quiver of the day's arrows slung over its right shoulder, the flights showing at the top.
    ("fletcher_cap", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -11, -4, 8, 3, 8, 0.6)], "fletcher"),
    ("fletcher_peak", "head", (0, 0, 0), (0, 0, 0), [(96, 0, -3.5, -8, -7, 7, 1, 3, 0)], "fletcher"),
    ("fletcher_feather", "head", (4.2, -9.5, 1.5), (-0.45, 0, 0.3), [(116, 0, -0.5, -6, -0.5, 1, 6, 1, 0)], "fletcher"),
    ("fletcher_apron", "body", (0, 0, 0), (0, 0, 0), [(64, 12, -3.5, 2, -4.5, 7, 13, 1, 0)], "fletcher"),
    ("fletcher_quiver", "body", (0, 2, 3.4), (0, 0, -0.35), [(84, 12, -1.5, -1, 0, 3, 10, 2, 0)], "fletcher"),
    ("fletcher_fletch", "body", (0, 2, 3.4), (0, 0, -0.35), [(96, 12, -1, -4, 0.5, 2, 3, 1, 0)], "fletcher"),

    # [golems] Golem keeper: a riveted leather skullcap, a pumpkin-orange scarf wound round its neck with an end hanging
    # down its chest, a heavy leather apron to the knee studded with iron rivets, and the shears it carves pumpkins
    # with at its hip.
    ("golemkeeper_cap", "head", (0, 0, 0), (0, 0, 0), [(64, 28, -4, -11, -4, 8, 2, 8, 0.6)], "golemkeeper"),
    ("golemkeeper_apron", "body", (0, 0, 0), (0, 0, 0), [(64, 0, -4, 1.5, -4.5, 8, 15, 1, 0)], "golemkeeper"),
    ("golemkeeper_scarf", "body", (0, 0, 0), (0, 0, 0), [(64, 17, -4.5, -1, -3.5, 9, 2, 7, 0.25)], "golemkeeper"),
    ("golemkeeper_scarf_end", "body", (0, 0, 0), (0, 0, 0), [(100, 0, 0.8, 0.6, -5.6, 2, 6, 1, 0)], "golemkeeper"),
    ("golemkeeper_shears", "body", (0, 0, 0), (0, 0, 0), [(108, 0, -5.6, 8.5, -1, 1, 3, 2, 0)], "golemkeeper"),
    # [individual] Who a folk is, in the round: what its own face picture (tools/folk_looks.py) paints, and only
    # that, on boxes no trade's picture touches (the bottom half of the sheet). A box its picture leaves clear is
    # not there at all, so these are worn-by "look": a bun on the folk that wears one, nothing on the rest.
    # Long hair down its back, a bun, a ponytail, a braid, hair tied back at the nape, the volume of curls or a
    # wild mop, and a long beard below the short one.
    ("hair_fall", "head", (0, 0, 0), (0, 0, 0), [(0, 60, -4, -0.5, 3.6, 8, 6, 1, 0)], "look"),
    ("hair_bun", "head", (0, 0, 0), (0, 0, 0), [(20, 60, -2, -9.5, 4.2, 4, 3, 3, 0)], "look"),
    ("hair_tail", "head", (0, -7.5, 4.4), (0.42, 0, 0), [(36, 60, -1, 0, -0.5, 2, 8, 2, 0)], "look"),
    ("hair_braid", "head", (0, -4.5, 4.3), (0.2, 0, 0), [(46, 60, -1, 0, -0.5, 2, 11, 2, 0)], "look"),
    ("hair_knot", "head", (0, -4, 4.4), (0.55, 0, 0), [(56, 60, -1, 0, -0.5, 2, 3, 2, 0)], "look"),
    ("hair_puff", "head", (0, 0, 0), (0, 0, 0), [(64, 60, -4, -10, -4, 8, 5, 8, 1.0)], "look"),
    ("beard_long", "head", (0, 0, 0), (0, 0, 0), [(96, 60, -3, 2, -5, 6, 6, 1, 0)], "look"),
    # Spectacles on its nose, the frames a block's sixteenth proud of its face.
    ("spectacles", "head", (0, 0, 0), (0, 0, 0), [(0, 76, -4, -7, -5, 8, 3, 1, 0)], "look"),
    # A pipe at the corner of its mouth, out only when it is smoking it (FolkModel: the manner).
    ("pipe", "head", (-2.5, -2, -4.5), (0.3, 0.25, 0), [(20, 76, -0.5, -0.5, -4, 1, 1, 4, 0),
                                                        (30, 76, -1, -2.5, -5, 2, 2, 2, 0)], "pipe"),
    # A walking stick in the hand that is not its working hand, its foot on the ground.
    ("cane_right", "right_arm", (-1, 9, -1), (0, 0, 0), [(40, 76, -0.5, -1, -0.5, 1, 14, 1, 0),
                                                          (44, 76, -0.5, -2, -2.5, 1, 1, 3, 0)], "cane"),
    ("cane_left", "left_arm", (1, 9, -1), (0, 0, 0), [(52, 76, -0.5, -1, -0.5, 1, 14, 1, 0),
                                                       (56, 76, -0.5, -2, -2.5, 1, 1, 3, 0)], "cane"),

    # [fireworks] Fireworks maker: a canvas apron gone grey with soot, two rockets in its pocket, brass goggles with
    # smoked lenses pushed up on its forehead, and a bright scarf of red and gold round its neck, its tail hanging down.
    ("fireworks_apron", "body", (0, 0, 0), (0, 0, 0), [(64, 0, -4, 1.5, -4.5, 8, 15, 1, 0)], "fireworks"),
    ("fireworks_band", "head", (0, 0, 0), (0, 0, 0), [(82, 0, -4, -9, -4, 8, 2, 8, 0.6)], "fireworks"),
    ("fireworks_lens_right", "head", (0, 0, 0), (0, 0, 0), [(114, 0, -3.2, -9.2, -5.7, 2, 2, 1, 0)], "fireworks"),
    ("fireworks_lens_left", "head", (0, 0, 0), (0, 0, 0), [(114, 0, 1.2, -9.2, -5.7, 2, 2, 1, 0)], "fireworks"),
    ("fireworks_scarf", "body", (0, 0, 0), (0, 0, 0), [(64, 20, -4.5, -0.5, -3.5, 9, 2, 7, 0.3)], "fireworks"),
    ("fireworks_tail", "body", (2.5, 0.8, -4.6), (-0.14, 0, 0.1), [(98, 20, -1, 0, -0.5, 2, 6, 1, 0)], "fireworks"),
    ("fireworks_rocket_a", "body", (0, 0, 0), (0, 0, 0), [(64, 32, -3.6, 5.5, -5.3, 1, 4, 1, 0)], "fireworks"),
    ("fireworks_rocket_b", "body", (0, 0, 0), (0, 0, 0), [(70, 32, -2.3, 6.5, -5.3, 1, 3, 1, 0)], "fireworks"),

    # [cartographer] Cartographer: a scholar's long blue coat (painted on the coat), round brass spectacles, a goose
    # quill tucked behind its right ear, a brass compass on a chain at its chest, and the day's map rolled under its
    # left arm, tied with a red ribbon, its ends poking out before and behind the arm.
    ("cartographer_specs", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -3.5, -7, -4.6, 7, 3, 1, 0)], "cartographer"),
    ("cartographer_quill", "head", (-4.4, -6, 1), (-0.45, 0, -0.2), [(96, 0, -0.5, -5, -0.5, 1, 6, 1, 0)], "cartographer"),
    ("cartographer_vane", "head", (-4.4, -6, 1), (-0.45, 0, -0.2), [(100, 0, -0.5, -5.5, -1.6, 1, 4, 2, 0)], "cartographer"),
    ("cartographer_compass", "body", (0, 0, 0), (0, 0, 0), [(108, 0, -1, 3, -3.9, 2, 2, 1, 0)], "cartographer"),
    ("cartographer_roll", "body", (0, 0, 0), (0, 0, 0), [(64, 8, 3.6, 2.5, -6, 3, 3, 11, 0)], "cartographer"),
    # [emerald] Emerald trader: a travelling merchant's long coat in emerald green, a wide-brimmed felt hat with an
    # emerald pinned in its band, a canvas pack on its back with a bedroll strapped over it and a little brass lantern
    # hung at its side, and a leather purse at its hip with an emerald for a clasp.
    ("emerald_crown", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -12, -4, 8, 3, 8, 0.6)], "emerald"),
    ("emerald_brim", "head", (0, 0, 0), (0, 0, 0), [(64, 11, -8, -9, -8, 16, 1, 16, 0)], "emerald"),
    ("emerald_pack", "body", (0, 0, 0), (0, 0, 0), [(64, 28, -4, 0.5, 3.6, 8, 10, 4, 0)], "emerald"),
    ("emerald_roll", "body", (0, 0, 0), (0, 0, 0), [(88, 28, -5, -2.5, 4.1, 10, 3, 3, 0)], "emerald"),
    ("emerald_lamp", "body", (0, 0, 0), (0, 0, 0), [(100, 36, 4.2, 3, 5, 1, 3, 2, 0)], "emerald"),
    ("emerald_purse", "body", (0, 0, 0), (0, 0, 0), [(114, 28, 2.5, 8.5, -4.6, 2, 3, 1, 0)], "emerald"),

    # [nether] Nether runner: a blackened iron skullcap banded in gold, a gold medallion at its brow (it stays on over
    # an iron helmet, and its stone glows), a mail curtain at the back of the neck, and the runner's satchel at its hip.
    ("netherrunner_helm", "head", (0, 0, 0), (0, 0, 0), [(64, 0, -4, -11, -4, 8, 4, 8, 0.6)], "netherrunner"),
    ("netherrunner_neckguard", "head", (0, 0, 0), (0, 0, 0), [(64, 12, -4.5, -7, 3.6, 9, 4, 1, 0)], "netherrunner"),
    ("netherrunner_crest", "head", (0, 0, 0), (0, 0, 0), [(104, 0, -1, -10, -5.6, 2, 2, 1, 0)], "netherrunner"),
    ("netherrunner_satchel", "body", (0, 0, 0), (0, 0, 0), [(100, 4, 4.0, 6, -2.5, 2, 5, 5, 0)], "netherrunner"),
]


def faces(u, v, w, h, d):
    """Where each face of a box sits on the picture: (x, y, width, height)."""
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + d + w + d, v + d, w, h),
    }


def box_of(name):
    for p in PARTS:
        if p[0] == name:
            c = p[4][0]
            return c[0], c[1], c[5], c[6], c[7]
    raise KeyError(name)


# ------------------------------------------------------------------- the canvas

class Canvas:
    def __init__(self):
        self.px = [[None] * SIZE for _ in range(SIZE)]

    def set(self, x, y, c):
        if 0 <= x < SIZE and 0 <= y < SIZE:
            self.px[y][x] = None if c is None else tuple(int(max(0, min(255, k))) for k in c[:3])

    def get(self, x, y):
        return self.px[y][x]

    def png(self):
        raw = b""
        for row in self.px:
            raw += b"\x00" + b"".join(
                bytes((c[0], c[1], c[2], 255)) if c is not None else b"\x00\x00\x00\x00" for c in row)

        def chunk(t, data):
            body = t + data
            return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
        return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def noise(x, y, seed=0):
    """A fixed hash of a pixel: -1 to 1."""
    n = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 32767.5 - 1.0


def mix(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def lit(c, f):
    return tuple(k * f for k in c)


def grain(c, x, y, amt=6, seed=0):
    d = noise(x, y, seed) * amt
    return tuple(k + d for k in c)


class Box:
    """A box on a canvas, painted face by face in each face's own coordinates."""

    def __init__(self, canvas, name):
        self.cv = canvas
        self.u, self.v, self.w, self.h, self.d = box_of(name)
        self.f = faces(self.u, self.v, self.w, self.h, self.d)

    def size(self, face):
        return self.f[face][2], self.f[face][3]

    def put(self, face, x, y, c):
        fx, fy, fw, fh = self.f[face]
        if 0 <= x < fw and 0 <= y < fh:
            self.cv.set(fx + x, fy + y, c)

    def at(self, face, x, y):
        fx, fy, fw, fh = self.f[face]
        return self.cv.get(fx + x, fy + y)

    def fill(self, face, fn):
        """fn(x, y, w, h) -> colour or None (leave) or False (clear)."""
        fx, fy, fw, fh = self.f[face]
        for y in range(fh):
            for x in range(fw):
                c = fn(x, y, fw, fh)
                if c is False:
                    self.cv.set(fx + x, fy + y, None)
                elif c is not None:
                    self.cv.set(fx + x, fy + y, c)

    def all(self, fn, which=("top", "bottom", "right", "front", "left", "back")):
        for face in which:
            self.fill(face, lambda x, y, w, h, face=face: fn(face, x, y, w, h))

    # The four sides as one strip, the way a belt or a hem goes round.
    SIDES = ("right", "front", "left", "back")

    def around(self, fn):
        """fn(s, y, strip_width, h, face, x) with s the distance round from the
        right side's back edge."""
        s0 = 0
        for face in self.SIDES:
            fw, fh = self.size(face)
            self.fill(face, lambda x, y, w, h, face=face, s0=s0: fn(s0 + x, y, 2 * (self.w + self.d), h, face, x))
            s0 += fw

    def row(self, y, c, faces_=SIDES):
        for face in faces_:
            fw, fh = self.size(face)
            for x in range(fw):
                self.put(face, x, y, c(x) if callable(c) else c)


# ----------------------------------------------------------------- the materials
#
# Every cloth is a function of the pixel, so a coat is never one flat colour.

def cloth(base, amt=7, seed=1):
    return lambda x, y: grain(base, x, y, amt, seed)


def denim(base=(62, 86, 140)):
    def f(x, y):
        c = base if (x + y) % 3 else lit(base, 0.86)        # the twill's diagonal
        return grain(c, x, y, 5, 2)
    return f


def plaid(red=(168, 38, 36), dark=(52, 22, 24)):
    red, dark = dyed(red, 236), dyed(dark, 84)

    def f(x, y):
        bx, by = x % 4 == 2, y % 4 == 2
        if bx and by:
            return grain(lit(dark, 0.7), x, y, 3, 3)
        if bx or by:
            return grain(mix(red, dark, 0.65), x, y, 4, 3)
        return grain(red, x, y, 6, 3)
    return f


def knit(base):
    def f(x, y):
        c = base if x % 2 == 0 else lit(base, 0.84)         # the ribs
        return grain(c, x, y, 5, 4)
    return f


def leather(base, scuff=0.12):
    def f(x, y):
        c = base
        n = noise(x, y, 5)
        if n > 0.82:
            c = lit(base, 1.0 + scuff)
        elif n < -0.85:
            c = lit(base, 1.0 - scuff * 1.4)
        return grain(c, x, y, 4, 6)
    return f


def straw(base=(224, 190, 104)):
    def f(x, y):
        if (x + 2 * y) % 4 == 0:
            c = lit(base, 0.82)
        elif (x - y) % 5 == 0:
            c = lit(base, 1.08)
        else:
            c = base
        return grain(c, x, y, 7, 7)
    return f


def steel(base=(158, 164, 172)):
    def f(x, y):
        return grain(base, x, y, 6, 8)
    return f


def wool(base=(228, 222, 206)):
    def f(x, y):
        n = noise(x, y, 9)
        c = lit(base, 1.06) if n > 0.4 else (lit(base, 0.9) if n < -0.45 else base)
        return grain(c, x, y, 4, 10)
    return f


def wicker(a=(176, 134, 72), b=(128, 92, 48)):
    def f(x, y):
        return grain(a if ((x // 1) + (y // 2)) % 2 == 0 else b, x, y, 6, 11)
    return f


def tweed(base=(118, 94, 70)):
    def f(x, y):
        c = lit(base, 1.1) if (x + (y // 2) * 2) % 4 == 0 else (lit(base, 0.88) if (x - y) % 4 == 0 else base)
        return grain(c, x, y, 5, 12)
    return f


def bark(base=(104, 78, 46)):
    def f(x, y):
        c = lit(base, 0.72) if (y * 3 + x // 3) % 4 == 0 or noise(x, y, 13) > 0.7 else base
        return grain(c, x, y, 7, 13)
    return f


# ------------------------------------------------------------------------ skins
#
# Who a folk is: skin, eyes, hair and a beard or not. Ten of them, chosen by the
# folk's id, so a village is a crowd of people and not a tray of copies.

SKIN_TONES = {
    "pale": (238, 204, 176),
    "fair": (226, 176, 136),
    "olive": (200, 152, 108),
    "tan": (176, 124, 84),
    "brown": (138, 94, 62),
    "dark": (98, 66, 46),
}
HAIR = {
    "black": (38, 32, 32),
    "brown": (92, 60, 36),
    "chestnut": (122, 70, 38),
    "auburn": (156, 72, 38),
    "blond": (214, 180, 104),
    "grey": (168, 166, 162),
    "white": (226, 224, 218),
}
EYES = {"blue": (64, 112, 186), "green": (64, 136, 72), "brown": (96, 62, 36), "grey": (110, 120, 130)}

# (skin, hair, eyes, style, beard)
SKINS = [
    ("fair", "brown", "blue", "short", False),
    ("tan", "black", "brown", "long", False),
    ("pale", "auburn", "green", "short", True),
    ("brown", "black", "brown", "cropped", False),
    ("olive", "chestnut", "green", "long", False),
    ("dark", "black", "brown", "cropped", True),
    ("pale", "blond", "blue", "long", False),
    ("fair", "grey", "grey", "balding", True),
    ("tan", "brown", "green", "short", False),
    ("brown", "white", "brown", "balding", False),
]


def paint_skin(skin, hair, eyes, style, beard):
    cv = Canvas()
    sk = SKIN_TONES[skin]
    hr = HAIR[hair]
    ey = EYES[eyes]
    skin_f = lambda x, y: grain(sk, x, y, 3, 20)
    hair_f = lambda x, y: grain(lit(hr, 0.92 if (x + y) % 3 == 0 else 1.0), x, y, 6, 21)
    dark_sk = lit(sk, 0.82)

    # The head: skin all round, hair on the crown and the back.
    head = Box(cv, "head")
    head.all(lambda face, x, y, w, h: skin_f(x, y))
    head.fill("top", lambda x, y, w, h: hair_f(x, y) if style != "balding" or (x in (0, 7) or y < 2) else skin_f(x, y))
    top_rows = {"short": 2, "long": 2, "cropped": 1, "balding": 0}[style]
    back_rows = {"short": 8, "long": 10, "cropped": 6, "balding": 7}[style]

    def side(face, x, y, w, h):
        # The sides: hair over the ear and the back, skin toward the face.
        # "right": x = 0 is the back edge; "left": x = 0 is the front edge.
        back_dist = x if face == "right" else (w - 1 - x)
        if style == "balding":
            return hair_f(x, y) if 3 <= y <= 6 and back_dist < 5 else None
        if back_dist < 3:
            rows = back_rows
        elif back_dist < 5:
            rows = max(top_rows + 2, back_rows - 3)
        else:
            rows = top_rows + 2
        return hair_f(x, y) if y < rows else None
    head.fill("right", lambda x, y, w, h: side("right", x, y, w, h))
    head.fill("left", lambda x, y, w, h: side("left", x, y, w, h))
    head.fill("back", lambda x, y, w, h: hair_f(x, y) if (style != "balding" and y < back_rows
                                                          or style == "balding" and 3 <= y <= 6) else None)
    # An ear on each side, a shade darker.
    for face in ("right", "left"):
        ex = 4 if face == "right" else 3
        head.put(face, ex, 5, dark_sk)
        head.put(face, ex, 6, lit(sk, 0.9))

    # The face.
    def face_px(x, y, w, h):
        if style != "balding" and y < top_rows:
            return hair_f(x, y)
        if style == "short" and y == top_rows and x in (0, 1, 6, 7):
            return hair_f(x, y)                                   # a fringe at the temples
        if style == "long" and y < 7 and x in (0, 7):
            return hair_f(x, y)                                   # hair framing the face
        return None
    head.fill("front", face_px)
    brow = lit(hr, 0.8) if hair not in ("white", "grey") else lit(hr, 0.7)
    for x in (1, 2, 5, 6):
        head.put("front", x, 3, brow)
    for x, c in ((1, (236, 236, 236)), (2, ey), (5, ey), (6, (236, 236, 236))):
        head.put("front", x, 4, c)
    head.put("front", 2, 5, lit(sk, 0.9))                          # under the eyes
    head.put("front", 5, 5, lit(sk, 0.9))
    head.put("front", 1, 6, mix(sk, (220, 110, 100), 0.25))         # a little colour in the cheeks
    head.put("front", 6, 6, mix(sk, (220, 110, 100), 0.25))
    head.put("front", 2, 8, lit(sk, 0.7))                           # the corners of a mouth
    head.put("front", 5, 8, lit(sk, 0.7))
    if beard:
        for y in range(6, 10):
            for x in range(8):
                if y >= 7 or x in (0, 1, 6, 7):
                    head.put("front", x, y, hair_f(x, y))
        for face in ("right", "left"):
            for y in range(6, 10):
                for x in range(8):
                    near = x >= 5 if face == "right" else x <= 2
                    if near:
                        head.put(face, x, y, hair_f(x, y))
    head.fill("bottom", lambda x, y, w, h: (hair_f(x, y) if beard and y >= 4 else lit(sk, 0.85)))

    # The hair's own layer: volume over the crown, and long hair down the back.
    hl = Box(cv, "hair")
    if style == "long":
        hl.fill("top", lambda x, y, w, h: hair_f(x, y))
        hl.fill("back", lambda x, y, w, h: hair_f(x, y) if y < 9 - (1 if x in (0, 7) else 0) else None)
        hl.fill("right", lambda x, y, w, h: hair_f(x, y) if (y < 2 or x < 4 and y < 8) else None)
        hl.fill("left", lambda x, y, w, h: hair_f(x, y) if (y < 2 or x > 3 and y < 8) else None)
        hl.fill("front", lambda x, y, w, h: hair_f(x, y) if y == 0 or (y < 6 and x in (0, 7)) else None)
    elif style in ("short", "cropped"):
        hl.fill("top", lambda x, y, w, h: hair_f(x, y) if noise(x, y, 22) > -0.5 else None)
        hl.fill("back", lambda x, y, w, h: hair_f(x, y) if y < (6 if style == "short" else 3) else None)
        hl.fill("right", lambda x, y, w, h: hair_f(x, y) if y == 0 else None)
        hl.fill("left", lambda x, y, w, h: hair_f(x, y) if y == 0 else None)
        if style == "short":
            hl.fill("front", lambda x, y, w, h: hair_f(x, y) if y == 0 and x not in (3,) else None)
    elif style == "balding":
        hl.fill("back", lambda x, y, w, h: hair_f(x, y) if 3 <= y <= 6 else None)
        hl.fill("right", lambda x, y, w, h: hair_f(x, y) if 3 <= y <= 6 and x < 4 else None)
        hl.fill("left", lambda x, y, w, h: hair_f(x, y) if 3 <= y <= 6 and x > 3 else None)

    nose = Box(cv, "nose")
    nose.all(lambda face, x, y, w, h: grain(lit(sk, 0.96), x, y, 3, 23))
    nose.put("bottom", 0, 0, lit(sk, 0.6))                          # nostrils
    nose.put("bottom", 1, 0, lit(sk, 0.6))
    nose.fill("front", lambda x, y, w, h: lit(sk, 1.04) if y == 0 else None)

    bd = Box(cv, "beard")
    bd.all(lambda face, x, y, w, h: hair_f(x + 3, y + 1))
    bd.fill("front", lambda x, y, w, h: False if (y == h - 1 and x in (0, w - 1)) else
            (lit(hr, 0.85) if y == 0 and x in (2, 3) else hair_f(x, y)))
    bd.fill("back", lambda x, y, w, h: False if (y == h - 1 and x in (0, w - 1)) else hair_f(x, y))

    # Plain clothes under everything: a linen shirt, trousers and shoes. Arms are
    # bare so a trade's sleeves can end where they end.
    linen = (196, 182, 150)
    trousers = (110, 92, 70)
    shoes = (62, 44, 32)
    body = Box(cv, "body")
    body.all(lambda face, x, y, w, h: grain(linen, x, y, 5, 24))
    coat = Box(cv, "coat")
    coat.around(lambda s, y, sw, h, face, x: grain(linen, s, y, 5, 25) if y < 12 else None)
    coat.fill("top", lambda x, y, w, h: grain(linen, x, y, 5, 25))
    for name in ("right_arm", "left_arm"):
        arm = Box(cv, name)
        arm.all(lambda face, x, y, w, h: grain(sk, x, y, 3, 26))
        arm.fill("bottom", lambda x, y, w, h: lit(sk, 0.92))
        arm.fill("front", lambda x, y, w, h: lit(sk, 0.9) if y == h - 1 and x in (1, 2) else None)
    for name in ("right_leg", "left_leg"):
        leg = Box(cv, name)
        leg.all(lambda face, x, y, w, h: grain(trousers, x, y, 5, 27) if y < 10 else grain(shoes, x, y, 4, 28))
        leg.fill("top", lambda x, y, w, h: grain(trousers, x, y, 5, 27))
        leg.fill("bottom", lambda x, y, w, h: lit(shoes, 0.7))
        leg.row(9, lit(trousers, 0.8))
        leg.fill("front", lambda x, y, w, h: lit(shoes, 1.25) if y == 10 and x in (1, 2) else None)
    return cv


# ---------------------------------------------------------------------- outfits

SLEEVE_TO = 8           # most sleeves end at the elbow-ish; hands from row 9

# The dyed parts. An outfit is painted twice: once in its own colours, once with
# its dyeable colours swapped for greys; whatever differs is the <trade>_dye.png
# mask, which the game tints with the wearer's colour (or its village's, for the
# watch) and lays over the outfit.
DYE_MODE = [False]


def dyed(c, grey):
    return (grey, grey, grey) if DYE_MODE[0] else c


def sleeves(cv, f, to=SLEEVE_TO, cuff=None, skip=()):
    for name in ("right_arm", "left_arm"):
        arm = Box(cv, name)
        arm.all(lambda face, x, y, w, h: (f(x + (0 if face != "back" else 2), y) if y <= to else None),
                which=("right", "front", "left", "back"))
        arm.fill("top", lambda x, y, w, h: f(x, y))
        if cuff:
            arm.row(to, cuff)


def legs(cv, trouser_f, boot_f, boot_from=9, sole=None):
    for name in ("right_leg", "left_leg"):
        leg = Box(cv, name)
        leg.around(lambda s, y, sw, h, face, x: trouser_f(s, y) if y < boot_from else boot_f(s, y))
        leg.fill("top", lambda x, y, w, h: trouser_f(x, y))
        leg.fill("bottom", lambda x, y, w, h: sole or lit(boot_f(x, y), 0.6))
        if sole:
            leg.row(11, sole)


def coat_to(cv, f, rows, top=None):
    """The coat: painted down to `rows` rows, gone below (so the legs show)."""
    coat = Box(cv, "coat")
    coat.around(lambda s, y, sw, h, face, x: f(s, y) if y < rows else False)
    coat.fill("top", lambda x, y, w, h: (top or f)(x, y))
    coat.fill("bottom", lambda x, y, w, h: lit(f(x, y), 0.7) if rows >= 18 else False)
    return coat


def belt(coat, y, strap=(70, 46, 30), buckle=(204, 172, 82)):
    coat.row(y, lambda x: grain(strap, x, y, 4, 30))
    coat.put("front", 3, y, buckle)
    coat.put("front", 4, y, lit(buckle, 0.75))


def outline_face(b, face, c):
    w, h = b.size(face)
    for x in range(w):
        b.put(face, x, 0, c)
        b.put(face, x, h - 1, c)
    for y in range(h):
        b.put(face, 0, y, c)
        b.put(face, w - 1, y, c)


def brim(b, f, edge, ragged=0, seed=0):
    """A hat brim: the top and the underside in f, a darker rim round it."""
    for face in ("top", "bottom"):
        def px(x, y, w, h, face=face):
            on_edge = x in (0, w - 1) or y in (0, h - 1)
            if ragged and on_edge and noise(x, y, seed) > 1 - ragged:
                return False
            c = f(x, y)
            if face == "bottom":
                c = lit(c, 0.78)
            return lit(edge, 1.0) if on_edge else c
        b.fill(face, px)
    for face in Box.SIDES:
        b.fill(face, lambda x, y, w, h: grain(edge, x, y, 5, seed))


def crown(b, f, band=None, band_rows=1):
    b.all(lambda face, x, y, w, h: f(x, y))
    if band:
        for r in range(band_rows):
            b.row(b.h - 1 - r, lambda x: grain(band, x, r, 4, 31))


def face_paint(cv, pts):
    head = Box(cv, "head")
    for (x, y, c) in pts:
        head.put("front", x, y, c)


def outfit_none():
    """A newcomer: an undyed wool tunic to the shins, a rope belt and a hood."""
    cv = Canvas()
    tunic = (150, 128, 96)
    f = cloth(tunic, 6, 40)
    coat = coat_to(cv, f, 18)
    coat.row(17, lambda x: lit(tunic, 0.78))
    coat.row(10, lambda x: grain((190, 168, 116), x, 10, 6, 41) if x % 2 else grain((160, 138, 92), x, 10, 6, 41))
    coat.put("front", 5, 11, (178, 156, 104))                       # the rope's end
    coat.put("front", 5, 12, (166, 144, 96))
    # A patch on the back.
    for x in range(2, 5):
        for y in range(4, 7):
            coat.put("back", x, y, grain((126, 104, 76), x, y, 4, 42))
    coat.put("back", 2, 4, (96, 80, 58))
    sleeves(cv, f, 9, cuff=lit(tunic, 0.82))
    # The hood, over the hair.
    hood = dyed((78, 92, 70), 226)
    hf = cloth(hood, 6, 43)
    hl = Box(cv, "hair")
    hl.fill("top", lambda x, y, w, h: hf(x, y))
    hl.fill("back", lambda x, y, w, h: hf(x, y))
    hl.fill("right", lambda x, y, w, h: hf(x, y))
    hl.fill("left", lambda x, y, w, h: hf(x, y))
    hl.fill("front", lambda x, y, w, h: lit(hood, 0.72) if (y == 0 or x in (0, 7)) else False)
    for face in ("right", "left"):                                   # the hood's seam
        w, h = hl.size(face)
        for y in range(h):
            hl.put(face, 4 if face == "right" else 3, y, lit(hood, 0.82))
    hl.fill("bottom", lambda x, y, w, h: False)
    # The cloak down its back, hood-coloured, with a clasp at the throat.
    ck = Box(cv, "none_cloak")
    ck.all(lambda face, x, y, w, h: grain(hood, x, y, 6, 45))
    ck.fill("back", lambda x, y, w, h: (lit(hood, 0.78) if x in (2, 6) and y > 2 else
                                        (lit(hood, 0.7) if y == h - 1 else grain(hood, x, y, 6, 45))))
    ck.fill("top", lambda x, y, w, h: lit(hood, 1.08))
    coat.put("front", 0, 0, lit(hood, 0.9))                         # the cloak's edge over the shoulders
    coat.put("front", 7, 0, lit(hood, 0.9))
    coat.put("front", 3, 1, (176, 150, 80))                         # a brass clasp
    coat.put("front", 4, 1, (150, 126, 66))
    legs(cv, cloth((96, 82, 62), 5, 44), leather((80, 58, 38)), boot_from=9)
    for name in ("right_leg", "left_leg"):
        leg = Box(cv, name)
        leg.row(6, (176, 160, 128))                                 # wrapped shins
        leg.row(8, (176, 160, 128))
    return cv


def outfit_farmer():
    """Denim overalls over a checked shirt, a red neckerchief, a straw hat."""
    cv = Canvas()
    shirt = (226, 214, 176)

    def checked(x, y):
        c = shirt
        if x % 3 == 0 or y % 3 == 0:
            c = mix(shirt, (180, 84, 66), 0.35)
        return grain(c, x, y, 4, 50)
    den = denim()
    coat = coat_to(cv, checked, 13)
    # The bib and straps, front and back.
    coat.fill("front", lambda x, y, w, h: den(x, y) if (y >= 3 and 1 <= x <= 6) or y >= 9 else
              (den(x, y) if x in (1, 6) else None))
    coat.fill("back", lambda x, y, w, h: den(x, y) if y >= 9 or (x in (1, 6) and y < 9) or
              (2 <= x <= 5 and y in (5, 6)) else None)
    coat.fill("right", lambda x, y, w, h: den(x, y) if y >= 9 else None)
    coat.fill("left", lambda x, y, w, h: den(x, y) if y >= 9 else None)
    coat.fill("top", lambda x, y, w, h: den(x, y) if x in (1, 6) else checked(x, y))
    for x, y in ((1, 3), (6, 3)):
        coat.put("front", x, y, (214, 182, 96))                     # brass buttons
    for x in range(2, 6):                                           # the bib pocket
        coat.put("front", x, 5, lit((62, 86, 140), 0.72))
    coat.put("front", 2, 6, lit((62, 86, 140), 0.72))
    coat.put("front", 5, 6, lit((62, 86, 140), 0.72))
    for x in range(1, 7):                                            # stitching at the waist
        if x % 2:
            coat.put("front", x, 9, (196, 168, 96))
    coat.row(12, lambda x: lit((62, 86, 140), 0.8))
    # The neckerchief: a red triangle at the throat.
    red = dyed((186, 48, 40), 236)
    for x in range(2, 6):
        coat.put("front", x, 0, grain(red, x, 0, 5, 51))
    coat.put("front", 3, 1, red)
    coat.put("front", 4, 1, lit(red, 0.85))
    coat.put("front", 4, 2, lit(red, 0.75))
    coat.row(0, lambda x: grain(red, x, 0, 5, 51), ("right", "left", "back"))
    sleeves(cv, checked, 7, cuff=lit(shirt, 0.86))
    legs(cv, denim(), leather((112, 74, 44)), boot_from=9, sole=(58, 40, 28))
    lg = Box(cv, "left_leg")
    for x in range(1, 3):                                            # a knee patch
        for y in range(4, 6):
            lg.put("front", x, y, grain((140, 108, 70), x, y, 4, 52))
    # The hat.
    st = straw()
    crown(Box(cv, "farmer_crown"), st, band=(160, 40, 36), band_rows=1)
    Box(cv, "farmer_crown").fill("top", lambda x, y, w, h: lit(st(x, y), 1.04))
    brim(Box(cv, "farmer_brim"), st, (176, 140, 70), ragged=0.25, seed=53)
    pouch = Box(cv, "farmer_pouch")
    pouch.all(lambda face, x, y, w, h: grain((156, 124, 82), x, y, 8, 54))
    pouch.fill("front", lambda x, y, w, h: (120, 92, 58) if y == 0 else None)
    pouch.put("front", 1, 1, (210, 196, 120))                        # seeds peeking out
    return cv


def outfit_lumberjack():
    """Red flannel, braces, canvas trousers, a knitted cap; logs on its back."""
    cv = Canvas()
    pl = plaid()
    coat = coat_to(cv, pl, 13)
    belt(coat, 11, buckle=(170, 172, 176))
    braces = (54, 40, 30)
    for y in range(0, 11):
        coat.put("front", 1, y, grain(braces, 1, y, 3, 60))
        coat.put("front", 6, y, grain(braces, 6, y, 3, 60))
        coat.put("back", 2, y, grain(braces, 2, y, 3, 60))
        coat.put("back", 5, y, grain(braces, 5, y, 3, 60))
    coat.put("front", 1, 8, (176, 178, 182))                        # the braces' clips
    coat.put("front", 6, 8, (176, 178, 182))
    for y in range(1, 9, 2):                                          # shirt buttons
        coat.put("front", 3, y, (40, 30, 26))
    coat.row(12, lambda x: lit(dyed((168, 38, 36), 236), 0.78))       # shirt tail
    sleeves(cv, pl, 7, cuff=(200, 200, 196))
    legs(cv, cloth((112, 92, 62), 6, 61), leather((58, 40, 28)), boot_from=8, sole=(34, 26, 22))
    for name in ("right_leg", "left_leg"):
        leg = Box(cv, name)
        leg.row(8, (150, 146, 140))                                   # sock tops
        for y in (9, 10):
            leg.put("front", 1, y, (196, 180, 140))                   # laces
            leg.put("front", 2, y, (196, 180, 140))
    # The cap, its cuff and its bobble.
    green = (54, 98, 70)
    crown(Box(cv, "lumberjack_cap"), knit(green))
    cap = Box(cv, "lumberjack_cap")
    cap.row(1, lambda x: grain((214, 196, 120), x, 1, 4, 62))        # a stripe
    cuff = Box(cv, "lumberjack_cuff")
    cuff.all(lambda face, x, y, w, h: knit(lit(green, 1.15))(x, y))
    Box(cv, "lumberjack_bobble").all(lambda face, x, y, w, h: wool((232, 226, 210))(x, y))
    # The frame and the logs.
    rack = Box(cv, "lumberjack_rack")
    rack.all(lambda face, x, y, w, h: grain((150, 116, 74), x, y, 6, 63))
    rack.fill("back", lambda x, y, w, h: (lit((150, 116, 74), 0.7) if x in (0, w - 1) or y in (0, h - 1)
                                          else (grain((86, 62, 40), x, y, 4, 64) if y % 3 == 0 else False)))
    for name in ("lumberjack_log_top", "lumberjack_log_low"):
        log = Box(cv, name)
        bk = bark((108, 80, 48) if name.endswith("top") else (96, 76, 52))
        log.all(lambda face, x, y, w, h: bk(x, y))
        for face in ("right", "left"):                                # the ends: rings
            log.fill(face, lambda x, y, w, h: (196, 158, 98) if (x, y) == (1, 1) else
                     ((178, 140, 84) if abs(x - 1) + abs(y - 1) == 1 else (120, 90, 54)))
        log.put("back", 4, 1, (82, 98, 50))                           # a bit of moss
        log.put("top", 7, 1, (82, 98, 50))
    return cv


def outfit_miner():
    """A dusty jacket, a tool belt, knee pads and a hard hat with a lamp."""
    cv = Canvas()
    jacket = (86, 84, 82)

    def dusty(x, y):
        c = grain(jacket, x, y, 6, 70)
        if noise(x, y, 71) > 0.78:
            c = lit(c, 0.7)                                           # coal dust
        return c
    coat = coat_to(cv, dusty, 14)
    for y in range(0, 11):                                             # leather harness
        coat.put("front", 2, y, grain((104, 70, 44), 2, y, 4, 72))
        coat.put("front", 5, y, grain((104, 70, 44), 5, y, 4, 72))
        coat.put("back", 2, y, grain((104, 70, 44), 2, y, 4, 72))
        coat.put("back", 5, y, grain((104, 70, 44), 5, y, 4, 72))
    for x in range(2, 6):
        coat.put("back", x, 4, grain((104, 70, 44), x, 4, 4, 72))     # crossing at the back
    belt(coat, 11, strap=(78, 52, 34), buckle=(186, 188, 192))
    for face, xs in (("right", (1, 2)), ("left", (3, 4))):            # belt pouches
        for x in xs:
            coat.put(face, x, 12, (118, 82, 50))
            coat.put(face, x, 13, (98, 68, 42))
    coat.row(13, lambda x: lit(jacket, 0.75), ("front", "back"))
    sleeves(cv, dusty, 8, cuff=lit(jacket, 0.8))
    for name in ("right_arm", "left_arm"):                            # gloves
        arm = Box(cv, name)
        arm.around(lambda s, y, sw, h, face, x: grain((132, 100, 66), s, y, 5, 73) if y >= 10 else None)
        arm.fill("bottom", lambda x, y, w, h: (112, 84, 54))
    legs(cv, cloth((70, 58, 48), 5, 74), leather((44, 36, 30)), boot_from=9, sole=(28, 24, 22))
    for name in ("right_leg", "left_leg"):
        leg = Box(cv, name)
        for x in range(4):
            for y in (4, 5):
                leg.put("front", x, y, grain((130, 96, 62), x, y, 4, 75))   # knee pads
        leg.put("front", 0, 11, (150, 152, 156))                      # steel toes
        leg.put("front", 3, 11, (150, 152, 156))
        leg.put("front", 1, 11, (180, 182, 186))
        leg.put("front", 2, 11, (180, 182, 186))
    # Soot on the face.
    face_paint(cv, [(0, 6, (96, 84, 76)), (7, 7, (96, 84, 76)), (6, 5, (110, 96, 86))])
    # The hard hat.
    ochre = (206, 160, 44)
    shell = Box(cv, "miner_shell")
    crown(shell, lambda x, y: grain(ochre, x, y, 6, 76))
    shell.fill("top", lambda x, y, w, h: (lit(ochre, 0.8) if x in (3, 4) else grain(lit(ochre, 1.06), x, y, 6, 76)))
    for face in Box.SIDES:                                              # rivets
        w, h = shell.size(face)
        shell.put(face, 1, h - 1, (120, 92, 30))
        shell.put(face, w - 2, h - 1, (120, 92, 30))
    shell.row(0, lambda x: lit(ochre, 0.8))                           # the ridge
    brim(Box(cv, "miner_brim"), lambda x, y: grain(ochre, x, y, 6, 77), lit(ochre, 0.68))
    lamp = Box(cv, "miner_lamp")
    lamp.all(lambda face, x, y, w, h: grain((150, 118, 52), x, y, 5, 78))
    lamp.fill("front", lambda x, y, w, h: (255, 244, 190) if 0 < x < w - 1 or y == 0 else (176, 140, 60))
    lantern = Box(cv, "miner_lantern")
    lantern.all(lambda face, x, y, w, h: ((58, 58, 62) if y in (0, h - 1) or x in (0, w - 1)
                                          else (252, 196, 92)))
    lantern.fill("top", lambda x, y, w, h: (46, 46, 50))
    lantern.fill("bottom", lambda x, y, w, h: (46, 46, 50))
    return cv


def miner_glow():
    cv = Canvas()
    lamp = Box(cv, "miner_lamp")
    lamp.fill("front", lambda x, y, w, h: (255, 236, 170) if 0 < x < w - 1 else None)
    lantern = Box(cv, "miner_lantern")
    for face in Box.SIDES:
        lantern.fill(face, lambda x, y, w, h: (250, 180, 70) if 0 < y < h - 1 and 0 < x < w - 1 else None)
    return cv


def outfit_cavedweller():
    """[caves] The cave team: a long oilskin coat with leather over its shoulders and at its elbows and brass
    buttons, canvas breeches and laced boots with steel toes, chalk on its gloves; a dented steel helm with a
    brass lamp strapped to its front; a coil of rope at its hip and a spare pick slung across its back. Not the
    miner's ochre hard hat and dusty jacket: the delver goes further down, and for longer."""
    cv = Canvas()
    oilskin = (70, 62, 40)

    def waxed(x, y):
        c = grain(oilskin, x, y, 5, 300)
        if (x + 2 * y) % 7 == 0:
            c = lit(c, 1.12)                                              # the wax's shine
        if noise(x, y, 301) > 0.86:
            c = lit(c, 0.78)                                              # mud
        return c
    coat = coat_to(cv, waxed, 16)
    yoke = leather((92, 62, 38))
    coat.row(0, lambda x: yoke(x, 0))                                     # leather over the shoulders
    coat.row(1, lambda x: yoke(x, 1))
    for y in range(2, 15):                                                # the coat's front edge and its buttons
        coat.put("front", 4, y, lit(oilskin, 0.72))
        if y % 3 == 0 and y < 10:
            coat.put("front", 3, y, (196, 160, 74))
    belt(coat, 9, strap=(58, 40, 26), buckle=(150, 152, 156))
    for face, xs in (("right", (1, 2, 3)), ("left", (2, 3, 4))):         # pouches on the belt
        for x in xs:
            coat.put(face, x, 10, (104, 72, 44))
            coat.put(face, x, 11, (86, 60, 38))
    for y in range(12, 16):                                               # a slit up the back, for the climbing
        coat.put("back", 4, y, lit(oilskin, 0.55))
    coat.row(15, lambda x: lit(oilskin, 0.7))
    sleeves(cv, waxed, 9, cuff=lit(oilskin, 0.75))
    for name in ("right_arm", "left_arm"):
        arm = Box(cv, name)
        for x in range(4):
            for y in (5, 6):
                arm.put("back", x, y, grain((92, 62, 38), x, y, 4, 302))  # leather at the elbows
        arm.around(lambda s, y, sw, h, face, x: grain((118, 92, 62), s, y, 5, 303) if y >= 10 else None)   # gloves
        arm.around(lambda s, y, sw, h, face, x: (214, 210, 196) if y == 11 and s % 3 == 0 else None)       # chalk
    legs(cv, cloth((92, 84, 66), 5, 304), leather((50, 38, 28)), boot_from=7, sole=(26, 22, 20))
    for name in ("right_leg", "left_leg"):
        leg = Box(cv, name)
        leg.row(7, (66, 50, 34))                                          # the boot tops
        leg.put("front", 1, 8, (176, 170, 150))                           # laced
        leg.put("front", 2, 9, (176, 170, 150))
        leg.put("front", 0, 11, (150, 152, 156))                          # steel toes
        leg.put("front", 3, 11, (150, 152, 156))
        leg.put("front", 1, 11, (178, 180, 184))
        leg.put("front", 2, 11, (178, 180, 184))
    face_paint(cv, [(1, 7, (110, 98, 88)), (6, 6, (104, 92, 82))])        # grime
    # The helm: dented steel, a leather band, a brass lamp.
    iron = (118, 122, 128)
    shell = Box(cv, "cavedweller_shell")
    crown(shell, lambda x, y: grain(lit(iron, 0.85) if noise(x, y, 305) > 0.7 else iron, x, y, 7, 306),
          band=(84, 58, 36), band_rows=1)
    shell.fill("top", lambda x, y, w, h: lit(iron, 1.12) if (x, y) in ((2, 2), (5, 4), (3, 6)) else grain(iron, x, y, 7, 306))
    brim(Box(cv, "cavedweller_brim"), lambda x, y: grain(lit(iron, 0.9), x, y, 6, 307), lit(iron, 0.6))
    lamp = Box(cv, "cavedweller_lamp")
    lamp.all(lambda face, x, y, w, h: grain((168, 130, 58), x, y, 5, 308) if (x + y) % 4 else (124, 94, 40))
    lamp.fill("front", lambda x, y, w, h: (255, 248, 206) if 0 < x < w - 1 and 0 < y < h - 1 else (140, 104, 44))
    # The spare pick across its back: an ash haft, an iron head.
    haft = Box(cv, "cavedweller_haft")
    haft.all(lambda face, x, y, w, h: grain((150, 112, 70), x, y, 5, 309) if y < h - 2 else (96, 70, 44))
    head = Box(cv, "cavedweller_pickhead")
    head.all(lambda face, x, y, w, h: (196, 200, 206) if x in (0, w - 1) else grain((136, 140, 148), x, y, 5, 310))
    # The rope: a coil of hemp, wound round.
    rope = Box(cv, "cavedweller_rope")
    rope.all(lambda face, x, y, w, h: (198, 170, 112) if (x + y) % 2 else (156, 128, 80))
    return cv


def outfit_fireworks():
    """[fireworks] The fireworks maker: a dark blue work shirt with its sleeves rolled to the elbow, soot on its
    forearms and its cheeks, leather gloves; a heavy canvas apron gone grey with powder and soot, a scorch or two
    and a burn hole, a pocket with two rockets standing in it (a red and a blue, paper-capped); brass goggles with
    smoked lenses pushed up on its forehead; and a bright scarf, red with gold stripes, round its neck, its fringed
    tail hanging down its front; canvas trousers and stout boots."""
    cv = Canvas()
    shirt = (50, 60, 100)
    sh = cloth(shirt, 6, 400)
    coat = coat_to(cv, sh, 12)
    coat.row(11, lambda x: lit(shirt, 0.72))
    canvas = (200, 190, 162)
    for x in (2, 5):                                                     # the apron's neck strap
        coat.put("front", x, 0, lit(canvas, 0.9))
        coat.put("front", x, 1, lit(canvas, 0.85))
    for face in ("right", "left", "back"):                              # its ties round the waist
        coat.row(9, lambda x: grain(lit(canvas, 0.85), x, 9, 4, 401), (face,))
    coat.put("back", 3, 10, lit(canvas, 0.8))
    coat.put("back", 4, 10, lit(canvas, 0.8))
    coat.put("back", 4, 11, lit(canvas, 0.7))
    coat.fill("top", lambda x, y, w, h: lit(canvas, 0.9) if x in (2, 5) else sh(x, y))
    sleeves(cv, sh, 4, cuff=lit(shirt, 1.22))                            # rolled up to the elbow
    soot = (74, 70, 68)
    for name in ("right_arm", "left_arm"):
        arm = Box(cv, name)
        arm.row(3, lambda x: lit(shirt, 1.1))                            # the roll
        for (face, x, y) in (("front", 1, 6), ("front", 2, 7), ("back", 0, 5), ("back", 3, 7), ("right", 2, 6), ("left", 1, 7)):
            arm.put(face, x, y, soot)                                     # soot on the bare forearms
        arm.around(lambda s, y, sw, h, face, x: grain((92, 66, 44), s, y, 5, 402) if y >= 9 else None)   # gloves
        arm.row(9, lambda x: (112, 82, 54))                              # the gloves' cuffs
        arm.fill("bottom", lambda x, y, w, h: (70, 50, 34))
    legs(cv, cloth((104, 96, 78), 5, 403), leather((60, 44, 32)), boot_from=8, sole=(28, 22, 18))
    for name in ("right_leg", "left_leg"):
        leg = Box(cv, name)
        leg.row(8, (80, 60, 42))                                         # the boot tops
        leg.put("front", 1, 2, lit(soot, 1.2))                           # a smudge where the hands wipe
    face_paint(cv, [(1, 7, (118, 98, 86)), (2, 8, (128, 108, 96)), (6, 6, (112, 94, 84))])   # soot on its cheeks
    # The apron: heavy canvas, grey with powder at the hem and soot where the hands go, scorched, a hole burnt in it.
    ap = Box(cv, "fireworks_apron")

    def apron(x, y, w, h):
        if y == 0 and x in (0, w - 1):
            return False
        c = grain(canvas, x, y, 6, 404)
        smut = noise(x, y, 405)
        if smut > 0.6:
            c = mix(c, soot, 0.2 + 0.5 * (smut - 0.6))                   # soot, in smudges
        if y >= h - 3:
            c = mix(c, (150, 146, 140), 0.35)                            # grey with powder at the hem
        if (x, y) in ((1, 3), (2, 4), (5, 11), (6, 12), (2, 13)):
            c = mix(c, soot, 0.6)                                        # where the hands wipe
        if (x, y) in ((6, 2), (5, 3)):
            c = (120, 84, 50)                                            # a scorch
        if (x, y) == (2, 11):
            c = (40, 34, 30)                                             # a hole burnt in it
        if x in (0, w - 1) or y == h - 1:
            c = lit(c, 0.82)
        if 1 <= x <= w - 2 and y == 8:
            c = lit(canvas, 0.68)                                        # the pocket's lip
        if 1 <= x <= w - 2 and y == 9:
            c = lit(c, 0.9)
        return c
    ap.fill("front", apron)
    ap.fill("back", lambda x, y, w, h: False if y == 0 and x in (0, w - 1) else lit(grain(canvas, x, y, 6, 404), 0.8))
    for face in ("right", "left", "top", "bottom"):
        ap.fill(face, lambda x, y, w, h: lit(canvas, 0.72))
    # The goggles: a leather strap round the head, brass rims, smoked glass with a glint.
    band = Box(cv, "fireworks_band")
    band.all(lambda face, x, y, w, h: grain((84, 58, 36), x, y, 4, 406))
    band.fill("top", lambda x, y, w, h: False if 1 <= x <= 6 and 1 <= y <= 6 else (84, 58, 36))
    band.fill("bottom", lambda x, y, w, h: False if 1 <= x <= 6 and 1 <= y <= 6 else (84, 58, 36))
    band.row(0, (106, 76, 48))
    for name in ("fireworks_lens_right", "fireworks_lens_left"):
        lens = Box(cv, name)
        lens.all(lambda face, x, y, w, h: (204, 164, 72) if (x + y) % 2 else (176, 136, 58))
        lens.fill("front", lambda x, y, w, h: (190, 222, 230) if (x, y) == (0, 0) else (58, 72, 84))
    # The scarf: red, striped gold, wound round the neck (hollow where the neck goes through).
    red, gold = (214, 38, 42), (246, 198, 46)
    sc = Box(cv, "fireworks_scarf")
    sc.around(lambda s, y, sw, h, face, x: grain(gold if (s // 2) % 3 == 2 else red, s, y, 6, 407) if y == 0
              else lit(grain(gold if (s // 2) % 3 == 2 else red, s, y, 6, 407), 0.82))
    sc.fill("top", lambda x, y, w, h: False if 1 <= x <= w - 2 and 1 <= y <= h - 2 else lit(red, 1.06))
    sc.fill("bottom", lambda x, y, w, h: False if 1 <= x <= w - 2 and 1 <= y <= h - 2 else lit(red, 0.7))
    tail = Box(cv, "fireworks_tail")
    tail.all(lambda face, x, y, w, h: ((250, 226, 132) if (x + y) % 2 else (214, 170, 52)) if y == h - 1   # the fringe
             else grain(gold if y % 3 == 1 else red, x, y, 6, 408))
    # Two rockets standing in the pocket: paper tubes, a red and a blue, a gold band, a grey fuse at the top.
    for name, body, band_c in (("fireworks_rocket_a", (188, 40, 38), (240, 200, 60)), ("fireworks_rocket_b", (52, 90, 186), (236, 232, 220))):
        rk = Box(cv, name)
        rk.all(lambda face, x, y, w, h, body=body, band_c=band_c: (band_c if y == 1 else lit(body, 1.0 if face in ("front", "left") else 0.82)))
        rk.fill("top", lambda x, y, w, h: (150, 150, 146))
    return cv


def outfit_cartographer():
    """[cartographer] A scholar's long coat of deep blue wool to the knee, its broad collar and turned-back cuffs in buff,
    brass buttons down its front, a claret waistcoat and a white stock at the throat; buff breeches buckled at the knee,
    grey stockings, black shoes with silver buckles; round brass spectacles; a goose quill behind its right ear, its nib
    black with ink; a brass compass on a chain; and the day's map rolled under its arm, tied with a red ribbon. An ink
    stain on its right cuff, as there always is."""
    cv = Canvas()
    navy = (40, 54, 96)
    buff = (206, 180, 126)
    claret = (124, 36, 46)
    brass = (214, 176, 84)

    def wool(x, y):
        c = grain(navy, x, y, 5, 600)
        if (x + 2 * y) % 5 == 0:
            c = lit(c, 1.1)                                               # the twill catching the light
        return c
    coat = coat_to(cv, wool, 16)
    coat.row(0, lambda x: grain(buff, x, 0, 4, 602))                      # the broad collar over the shoulders
    for y in range(0, 7):                                                 # open at the chest: the waistcoat
        coat.put("front", 3, y, grain(claret, 3, y, 4, 601))
        coat.put("front", 4, y, grain(claret, 4, y, 4, 601))
    for x in (3, 4):                                                      # the white stock at the throat
        coat.put("front", x, 0, (238, 234, 222))
        coat.put("front", x, 1, (224, 220, 208))
    coat.put("front", 4, 2, brass)                                        # the compass's chain
    coat.put("front", 3, 5, (196, 164, 76))                               # a waistcoat button
    for y in range(1, 7):                                                 # the lapels, buff
        coat.put("front", 2, y, lit(buff, 0.94 if y % 2 else 0.86))
        coat.put("front", 5, y, lit(buff, 0.94 if y % 2 else 0.86))
    for y in range(7, 16):                                                # closed below: the seam, and the buttons
        coat.put("front", 4, y, lit(navy, 0.72))
    for y in range(8, 15, 2):
        coat.put("front", 2, y, brass)
        coat.put("front", 5, y, lit(brass, 0.85))
    for x in (0, 1, 6, 7):                                                # the pocket flaps
        coat.put("front", x, 10, lit(navy, 0.66))
        coat.put("front", x, 11, lit(navy, 0.82))
    for x in range(8):
        coat.put("front", x, 15, lit(navy, 0.68))                         # the hem
        coat.put("back", x, 15, lit(navy, 0.68))
    for y in range(10, 15):                                               # the vent up the back
        coat.put("back", 4, y, lit(navy, 0.6))
    coat.put("back", 3, 9, brass)
    coat.put("back", 5, 9, brass)
    for face in ("right", "left"):                                        # side seams
        w, h = coat.size(face)
        for y in range(1, 15):
            coat.put(face, w // 2, y, lit(navy, 0.82))
    sleeves(cv, wool, 9, cuff=buff)
    for name in ("right_arm", "left_arm"):
        arm = Box(cv, name)
        arm.row(8, lambda x: grain(buff, x, 8, 4, 603))                  # the cuffs, turned back
        arm.row(9, lambda x: lit(buff, 0.86))
    Box(cv, "right_arm").put("front", 1, 9, (34, 30, 52))                 # the ink stain
    Box(cv, "right_arm").put("front", 2, 8, (48, 44, 70))
    legs(cv, cloth((180, 154, 104), 5, 604), leather((30, 26, 24)), boot_from=10, sole=(18, 16, 14))
    for name in ("right_leg", "left_leg"):
        leg = Box(cv, name)
        leg.row(5, lambda x: lit(buff, 0.8))                              # the breeches' knee band
        leg.put("front", 1, 5, (200, 200, 206))                           # its buckle
        for y in range(6, 10):
            leg.row(y, lambda x, y=y: grain((150, 150, 158), x, y, 4, 605))   # grey stockings
        leg.put("front", 1, 10, (206, 206, 214))                          # the shoes' silver buckles
        leg.put("front", 2, 10, (176, 176, 186))
    # The spectacles: two round brass rims and the bridge, the eyes showing through.
    specs = Box(cv, "cartographer_specs")
    specs.all(lambda face, x, y, w, h: False)

    def rims(x, y, w, h):
        if x == 3:
            return brass if y == 1 else False
        if y == 1 and x in (1, 5):
            return False
        return lit(brass, 0.9 if (x + y) % 2 else 1.05)
    specs.fill("front", rims)
    specs.fill("right", lambda x, y, w, h: lit(brass, 0.8) if y == 0 else False)
    specs.fill("left", lambda x, y, w, h: lit(brass, 0.8) if y == 0 else False)
    # The quill: a white goose feather, its tip grey, its nib black with ink.
    quill = Box(cv, "cartographer_quill")
    quill.all(lambda face, x, y, w, h: (40, 36, 50) if y >= h - 2 else (232, 230, 222))
    vane = Box(cv, "cartographer_vane")
    vane.all(lambda face, x, y, w, h: (150, 150, 156) if y == 0 else ((246, 244, 238) if (x + y) % 3 else (218, 214, 204)))
    # The compass on its chain: a brass case, a white face, a red needle.
    comp = Box(cv, "cartographer_compass")
    comp.all(lambda face, x, y, w, h: lit(brass, 0.85))
    comp.fill("front", lambda x, y, w, h: [[(240, 236, 220), (196, 40, 40)], [brass, (240, 236, 220)]][y][x])
    # The map, rolled: parchment, a few ink lines showing, a red ribbon round its middle, the rolled ends.
    roll = Box(cv, "cartographer_roll")

    def parchment(face, x, y, w, h):
        long = face in ("top", "bottom")
        along = y if long else x
        if along == 5:
            return (176, 40, 40) if (x + y) % 3 else (148, 30, 30)       # the ribbon
        c = grain((234, 218, 172), x, y, 6, 606)
        if face in ("right", "left") and y == 1 and along % 4 == 1:
            c = (128, 96, 62)                                             # ink showing through
        return c
    roll.all(parchment, which=("top", "bottom", "right", "left"))
    for face in ("front", "back"):
        roll.fill(face, lambda x, y, w, h: (150, 120, 76) if (x, y) == (1, 1) else ((204, 182, 130) if (x + y) % 2 else (226, 208, 160)))
    return cv


def outfit_emerald():
    """[emerald] The emerald trader: a travelling merchant's long coat in emerald green, its front edges and cuffs
    trimmed in gold braid and its lapels a darker green, brass buttons, a cream shirt collar and a red sash for a belt;
    brown breeches and tall riding boots for the road; a wide-brimmed brown felt hat, its band green with an emerald
    pinned to it; a canvas pack on its back, strapped and buckled, a grey wool bedroll across the top of it and a small
    brass lantern hung at its side; and a leather purse at its hip, clasped with an emerald."""
    cv = Canvas()
    green = (28, 128, 74)
    deep = (16, 84, 48)
    gold = (214, 178, 76)

    def coat_f(x, y):
        c = grain(green, x, y, 6, 320)
        if (x + y) % 6 == 0:
            c = lit(c, 1.08)                                              # the weave's sheen
        return c
    coat = coat_to(cv, coat_f, 17)
    # The lapels, the shirt collar at the throat, and the gold braid down both front edges.
    for y in range(0, 5):
        coat.put("front", 2, y, grain(deep, 2, y, 4, 321))
        coat.put("front", 5, y, grain(deep, 5, y, 4, 321))
    for x in range(3, 5):
        coat.put("front", x, 0, (232, 222, 196))
        coat.put("front", x, 1, (214, 204, 176))
    coat.row(0, lambda x: (232, 222, 196), ("right", "left", "back"))
    for y in range(5, 17):
        coat.put("front", 3, y, grain(gold, 3, y, 6, 322))
        coat.put("front", 4, y, lit(green, 0.7))
        if y in (6, 8, 13, 15):
            coat.put("front", 5, y, (236, 204, 104))                     # brass buttons
    # A red sash round its middle, its knot at the left hip.
    sash = dyed((168, 40, 44), 230)
    coat.row(10, lambda x: grain(sash, x, 10, 6, 323))
    coat.row(11, lambda x: grain(lit(sash, 0.86), x, 11, 6, 323))
    coat.put("left", 1, 12, sash)
    coat.put("left", 1, 13, lit(sash, 0.8))
    # Pocket flaps, the hem in braid, and a vent up the back for the saddle.
    for x in (1, 2):
        coat.put("front", x, 13, lit(green, 0.66))
    for x in (5, 6):
        coat.put("front", x, 13, lit(green, 0.66))
    coat.row(16, lambda x: grain(gold, x, 16, 6, 324))
    for y in range(12, 16):
        coat.put("back", 4, y, lit(green, 0.55))
    # The sleeves, to the wrist, turned back in a gold-trimmed cuff.
    sleeves(cv, coat_f, 9, cuff=lit(gold, 0.95))
    for name in ("right_arm", "left_arm"):
        arm = Box(cv, name)
        arm.row(8, lambda x: grain(deep, x, 8, 4, 325))
    # Breeches and tall riding boots.
    legs(cv, cloth((112, 86, 58), 5, 326), leather((62, 40, 26)), boot_from=4, sole=(30, 22, 16))
    for name in ("right_leg", "left_leg"):
        leg = Box(cv, name)
        leg.row(4, (88, 60, 38))                                          # the boot tops, folded over
        leg.put("front", 1, 7, (110, 76, 46))                             # a highlight down the shin
        leg.put("front", 1, 8, (110, 76, 46))
    face_paint(cv, [(2, 8, (200, 150, 120))])                             # sun on its cheek from the road
    # The hat: brown felt, a green band with an emerald pinned in it, a wide brim with a darker rim.
    felt = (92, 64, 42)
    crown(Box(cv, "emerald_crown"), cloth(felt, 6, 327), band=deep, band_rows=1)
    hat = Box(cv, "emerald_crown")
    hat.fill("top", lambda x, y, w, h: lit(grain(felt, x, y, 6, 327), 1.06) if 2 <= x <= 5 and 3 <= y <= 4 else grain(felt, x, y, 6, 327))
    hat.put("front", 3, 2, (90, 230, 150))                                # the emerald on the band
    hat.put("front", 4, 2, (40, 176, 96))
    hat.put("front", 3, 1, (180, 250, 210))
    brim(Box(cv, "emerald_brim"), cloth(felt, 6, 328), lit(felt, 0.6), ragged=0.1, seed=329)
    # The pack: canvas, two leather straps with brass buckles, a flap over the top.
    canvas = (176, 156, 112)
    pack = Box(cv, "emerald_pack")
    pack.all(lambda face, x, y, w, h: grain(canvas, x, y, 7, 330) if (x + 2 * y) % 5 else grain(lit(canvas, 0.9), x, y, 7, 330))
    pack.fill("back", lambda x, y, w, h: (96, 66, 40) if x in (1, w - 2) else (lit(canvas, 0.8) if y < 3 else None))
    pack.fill("back", lambda x, y, w, h: (220, 188, 92) if x in (1, w - 2) and y == 6 else None)
    pack.fill("top", lambda x, y, w, h: lit(canvas, 0.86))
    pack.fill("back", lambda x, y, w, h: (36, 150, 84) if (x, y) == (w // 2, 1) else None)   # a trader's mark: an emerald stitched on
    roll = Box(cv, "emerald_roll")
    roll.all(lambda face, x, y, w, h: grain((128, 130, 140), x, y, 6, 331) if (x + y) % 3 else (104, 106, 116))
    roll.fill("front", lambda x, y, w, h: (96, 66, 40) if x in (2, w - 3) else None)
    roll.fill("back", lambda x, y, w, h: (96, 66, 40) if x in (2, w - 3) else None)
    roll.fill("top", lambda x, y, w, h: (96, 66, 40) if x in (2, w - 3) else None)
    for face in ("right", "left"):                                         # the roll's ends, wound
        roll.fill(face, lambda x, y, w, h: (150, 152, 162) if (x + y) % 2 else (112, 114, 124))
    lamp = Box(cv, "emerald_lamp")
    lamp.all(lambda face, x, y, w, h: (176, 136, 62) if y in (0, h - 1) else (250, 196, 96))
    purse = Box(cv, "emerald_purse")
    purse.all(lambda face, x, y, w, h: grain((116, 78, 46), x, y, 5, 332))
    purse.fill("front", lambda x, y, w, h: (48, 200, 116) if (x, y) == (0, 0) else ((24, 140, 78) if (x, y) == (1, 0) else None))
    return cv


def cavedweller_glow():
    """[caves] The cave dweller's helm lamp, lit whatever the light around it."""
    cv = Canvas()
    lamp = Box(cv, "cavedweller_lamp")
    lamp.fill("front", lambda x, y, w, h: (255, 244, 186) if 0 < x < w - 1 and 0 < y < h - 1 else None)
    return cv


def outfit_netherrunner():
    """[nether] The Nether runners: a long coat gone soot-dark and scorched at the hem from the heat, gold trim down
    its front and at its cuffs (gold is what the piglins look for), leather over the shoulders, a crimson scarf at the
    throat against the ash; blackened breeches and boots with gold buckles; a blackened iron skullcap banded in gold
    with a gold medallion at its brow (the medallion stays on over an iron helmet, and its stone glows), a mail curtain
    at the back of the neck; and the runner's satchel at its hip, waxed with magma cream, its seams stitched orange."""
    cv = Canvas()
    soot = (46, 40, 38)
    gold = (222, 174, 58)
    dark_gold = (156, 108, 32)

    def scorched(x, y):
        c = grain(soot, x, y, 5, 400)
        n = noise(x, y, 401)
        if n > 0.80:
            c = grain((74, 46, 34), x, y, 4, 402)                           # scorch
        elif n < -0.88:
            c = lit(c, 0.72)                                                  # ash
        return c
    coat = coat_to(cv, scorched, 17)
    yoke = leather((88, 56, 38))
    coat.row(0, lambda x: yoke(x, 0))                                         # leather over the shoulders
    coat.row(1, lambda x: yoke(x, 1))
    for y in range(2, 17):                                                    # gold trim down the front edges
        coat.put("front", 3, y, gold if y % 4 else dark_gold)
        coat.put("front", 4, y, lit(gold, 0.86) if y % 4 else dark_gold)
    # The scarf at the throat: crimson, its ends down the front.
    scarf = (150, 38, 34)
    coat.row(0, lambda x: grain(scarf, x, 0, 5, 403), ("front",))
    for y in (1, 2, 3):
        coat.put("front", 2, y, grain(scarf if y < 3 else lit(scarf, 0.8), 2, y, 5, 403))
    belt(coat, 10, strap=(52, 36, 26), buckle=gold)
    coat.put("front", 3, 10, gold)
    coat.put("front", 4, 10, lit(gold, 0.7))
    # The satchel's strap across the chest, shoulder to hip.
    for y in range(1, 10):
        coat.put("front", min(7, 1 + (y * 6) // 9), y, grain((104, 70, 46), y, 1, 4, 404))
        coat.put("back", max(0, 6 - (y * 6) // 9), y, grain((104, 70, 46), y, 2, 4, 404))
    # The hem: scorched to ember and burnt ragged.
    for face in Box.SIDES:
        w, h = coat.size(face)
        for x in range(w):
            coat.put(face, x, 16, (128, 58, 26) if (x + len(face)) % 3 else (196, 96, 34))
            if noise(x, 15, 405) > 0.5:
                coat.put(face, x, 15, (92, 48, 30))
    sleeves(cv, scorched, 9, cuff=gold)
    for name in ("right_arm", "left_arm"):
        arm = Box(cv, name)
        arm.around(lambda s, y, sw, h, face, x: grain((76, 52, 38), s, y, 5, 406) if y >= 10 else None)   # gauntlets
        arm.row(10, lambda x: lit((76, 52, 38), 1.2))
    legs(cv, cloth((52, 44, 40), 5, 407), leather((34, 28, 26)), boot_from=7, sole=(20, 16, 14))
    for name in ("right_leg", "left_leg"):
        leg = Box(cv, name)
        leg.row(7, (58, 44, 34))                                              # the boot tops
        leg.put("front", 1, 8, gold)                                          # gold buckles
        leg.put("front", 2, 8, lit(gold, 0.75))
        leg.put("front", 0, 11, (60, 58, 62))
        leg.put("front", 3, 11, (60, 58, 62))
    face_paint(cv, [(0, 7, (92, 76, 70)), (7, 6, (92, 76, 70)), (6, 7, (100, 84, 76))])   # soot
    # The skullcap: blackened iron, banded in gold, a gold ridge over the crown.
    iron = (66, 64, 70)
    helm = Box(cv, "netherrunner_helm")
    crown(helm, lambda x, y: grain(lit(iron, 1.18) if noise(x, y, 408) > 0.75 else iron, x, y, 6, 409), band=gold, band_rows=1)
    helm.fill("top", lambda x, y, w, h: (gold if x in (3, 4) else grain(lit(iron, 1.08), x, y, 6, 409)))
    for face in Box.SIDES:
        w, h = helm.size(face)
        helm.put(face, 1, h - 2, dark_gold)                                    # rivets on the band
        helm.put(face, w - 2, h - 2, dark_gold)
    neck = Box(cv, "netherrunner_neckguard")
    neck.all(lambda face, x, y, w, h: (96, 94, 100) if (x + y) % 2 == 0 else (44, 42, 48))   # mail
    neck.row(0, dark_gold)
    crest = Box(cv, "netherrunner_crest")
    crest.all(lambda face, x, y, w, h: gold)
    crest.fill("front", lambda x, y, w, h: (182, 32, 40) if (x, y) == (0, 1) or (x, y) == (1, 0) else
               ((226, 70, 60) if (x, y) == (0, 0) else (120, 18, 26)))
    # The satchel at its hip: scorched leather, a flap, orange stitching, a gold buckle.
    sat = Box(cv, "netherrunner_satchel")
    sat.all(lambda face, x, y, w, h: grain((70, 47, 37), x, y, 5, 410))
    sat.fill("top", lambda x, y, w, h: grain((97, 66, 48), x, y, 5, 411))
    for face in Box.SIDES:
        w, h = sat.size(face)
        for x in range(w):
            sat.put(face, x, 0, grain((97, 66, 48), x, 0, 5, 411))          # the flap's edge
            sat.put(face, x, 1, (232, 112, 28) if x % 2 == 0 else (150, 52, 12))   # stitched with the wax
            sat.put(face, x, h - 1, (40, 28, 22))
    sat.put("left", 2, 2, gold)
    sat.put("left", 2, 3, dark_gold)
    sat.put("right", 2, 2, gold)
    return cv


def netherrunner_glow():
    """[nether] The stone in the runner's brow medallion, and the embers in its coat's hem, glowing in the dark."""
    cv = Canvas()
    crest = Box(cv, "netherrunner_crest")
    crest.fill("front", lambda x, y, w, h: (255, 96, 70) if (x, y) in ((0, 0), (0, 1), (1, 0)) else (200, 40, 40))
    coat = Box(cv, "coat")
    for face in Box.SIDES:
        w, h = coat.size(face)
        for x in range(w):
            if (x + len(face)) % 3 == 0:
                coat.put(face, x, 16, (255, 140, 60))
    return cv


def outfit_rancher():
    """A leather waistcoat over a blue shirt, chaps, a wool shawl, a wide hat."""
    cv = Canvas()
    shirt = dyed((128, 160, 196), 232)
    vest = (148, 100, 58)
    sh = cloth(shirt, 5, 80)
    lv = leather(vest)

    def body_px(s, y):
        return sh(s, y)
    coat = coat_to(cv, body_px, 13)
    coat.fill("front", lambda x, y, w, h: lv(x, y) if x in (0, 1, 2, 5, 6, 7) and y < 11 else None)
    coat.fill("right", lambda x, y, w, h: lv(x, y) if y < 11 else None)
    coat.fill("left", lambda x, y, w, h: lv(x, y) if y < 11 else None)
    coat.fill("back", lambda x, y, w, h: lv(x, y) if y < 11 else None)
    for y in range(1, 10, 2):
        coat.put("front", 3, y, (236, 232, 222))                       # shirt buttons
    for y in range(2, 10, 3):
        coat.put("front", 2, y, lit(vest, 1.2))                        # stitching on the lapels
        coat.put("front", 5, y, lit(vest, 1.2))
    belt(coat, 11, strap=(64, 42, 28), buckle=(214, 196, 150))
    coat.put("front", 2, 11, (214, 196, 150))                          # a big buckle
    coat.put("front", 5, 11, (214, 196, 150))
    coat.row(12, lambda x: lit(shirt, 0.8))
    sleeves(cv, sh, 7, cuff=lit(shirt, 0.82))
    chaps = (122, 82, 50)
    jeans = denim((70, 92, 132))

    def chaps_px(s, y):
        # Leather chaps on the fronts and outsides, denim showing at the back.
        return leather(chaps)(s, y) if (s % 16) < 12 else jeans(s, y)
    legs(cv, chaps_px, leather((86, 56, 34)), boot_from=9, sole=(40, 28, 20))
    for name in ("right_leg", "left_leg"):
        leg = Box(cv, name)
        for y in range(0, 9, 2):
            leg.put("left" if name == "left_leg" else "right", 1, y, (220, 196, 150))   # fringe stitching
        leg.put("front", 1, 9, (196, 198, 202))                       # spurs' glint
    # The hat.
    felt = (118, 84, 54)
    cr = Box(cv, "rancher_crown")
    crown(cr, lambda x, y: grain(felt, x, y, 5, 81), band=(52, 36, 26))
    cr.fill("top", lambda x, y, w, h: lit(felt, 0.7) if x in (3, 4) and 1 <= y <= 6 else grain(felt, x, y, 5, 81))
    cr.put("left", 2, 2, (196, 70, 50))                                # a feather in the band
    cr.put("left", 2, 1, (220, 120, 70))
    brim(Box(cv, "rancher_brim"), lambda x, y: grain(felt, x, y, 5, 82), lit(felt, 0.75))
    curl = Box(cv, "rancher_curl_right")
    curl.all(lambda face, x, y, w, h: grain(lit(felt, 0.9), x, y, 5, 83))
    # The shawl: wool with a fringe.
    shawl = Box(cv, "rancher_shawl")
    wl = wool((222, 214, 196))
    shawl.all(lambda face, x, y, w, h: wl(x, y))
    for face in Box.SIDES:
        w, h = shawl.size(face)
        for x in range(w):
            shawl.put(face, x, h - 1, wl(x, h - 1) if x % 2 else None)
            shawl.put(face, x, 1, grain((150, 60, 50), x, 1, 5, 84))   # a woven red stripe
    shawl.fill("bottom", lambda x, y, w, h: False)
    rope = Box(cv, "rancher_rope")
    rope.all(lambda face, x, y, w, h: grain((198, 168, 112), x, y, 6, 85))
    rope.fill("front", lambda x, y, w, h: (150, 122, 78) if (x, y) == (1, 1) else
              ((214, 186, 128) if (x + y) % 2 else (176, 146, 96)))
    return cv


# The village bell, the watch's badge: 6 x 6, rows from y0. "h" lit, "g" gold,
# "s" shaded, "c" the clapper.
BELL_ROWS = ["  h   ",
             " hgs  ",
             " hgs  ",
             "hggss ",
             "gggsss",
             "  c   "]
BELL = {(x + 1, y + 2): ch for y, row in enumerate(BELL_ROWS) for x, ch in enumerate(row) if ch != " "}


def bell_px(ch, gold):
    return {"h": lit(gold, 1.16), "g": gold, "s": lit(gold, 0.78), "c": lit(gold, 0.6)}[ch]


def bell(put, y0, gold):
    for (x, y), ch in BELL.items():
        put(x, y + y0 - 1, bell_px(ch, gold))


def outfit_guard():
    """A quilted gambeson under the village's blue tabard, a kettle hat, a shield."""
    cv = Canvas()
    gamb = (150, 140, 116)
    blue = dyed((44, 64, 128), 236)
    gold = (220, 182, 70)

    def quilt(s, y):
        c = lit(gamb, 0.82) if (s + y) % 4 == 0 or (s - y) % 4 == 0 else gamb
        return grain(c, s, y, 4, 90)

    def tab(x, y):
        return grain(blue, x, y, 5, 91)
    coat = coat_to(cv, quilt, 17)
    coat.fill("front", lambda x, y, w, h: (tab(x, y) if 1 <= x <= 6 else None))
    coat.fill("back", lambda x, y, w, h: (tab(x, y) if 1 <= x <= 6 else None))
    coat.fill("right", lambda x, y, w, h: None if y < 12 else False)
    coat.fill("left", lambda x, y, w, h: None if y < 12 else False)
    for face in ("front", "back"):
        for y in range(12, 17):
            coat.put(face, 0, y, None)
            coat.put(face, 7, y, None)
        for x in range(1, 7):                                           # gold hem
            coat.put(face, x, 16, gold if x % 2 else lit(gold, 0.8))
        # The village's bell, in gold.
        bell(lambda x, y, c, face=face: coat.put(face, x, y, c), 1, gold)
    coat.fill("bottom", lambda x, y, w, h: False)
    belt(coat, 10, strap=(36, 30, 28), buckle=(196, 198, 204))
    sleeves(cv, quilt, 9)
    for name in ("right_arm", "left_arm"):
        arm = Box(cv, name)
        arm.around(lambda s, y, sw, h, face, x: (grain((96, 64, 40), s, y, 4, 92) if y >= 9 else None))
        arm.row(9, (118, 82, 52))
        arm.fill("bottom", lambda x, y, w, h: (86, 58, 36))
    legs(cv, cloth((62, 58, 56), 5, 93), leather((92, 62, 38)), boot_from=6, sole=(34, 26, 22))
    for name in ("right_leg", "left_leg"):
        Box(cv, name).row(6, (118, 82, 52))                            # boot tops turned down
    # The kettle hat.
    st = steel()
    shell = Box(cv, "guard_shell")
    crown(shell, st)
    shell.fill("top", lambda x, y, w, h: lit(st(x, y), 1.1) if (x + y) % 5 else lit(st(x, y), 1.25))
    shell.row(0, lambda x: lit((158, 164, 172), 1.18))
    for face in Box.SIDES:
        w, h = shell.size(face)
        for x in range(0, w, 3):
            shell.put(face, x, h - 1, (96, 100, 108))                  # rivets
    brim(Box(cv, "guard_brim"), st, (112, 116, 124))
    # The shield: a heater in the village colours.
    sh = Box(cv, "guard_shield")
    def shield(x, y, w, h):
        cut = (y == h - 1 and x not in (3, 4)) or (y == h - 2 and x in (0, 7)) or (y == h - 3 and x in (0, 7) and False)
        if cut:
            return False
        if x in (0, 7) or y == 0 or (y == h - 1) or (y == h - 2 and x in (1, 6)):
            return (170, 174, 182)                                      # steel rim
        b = BELL.get((x, y - 1))
        if b is not None:
            return bell_px(b, gold)
        return grain(blue, x, y, 5, 94)
    sh.fill("back", shield)
    sh.fill("front", lambda x, y, w, h: False if shield(w - 1 - x, y, w, h) is False else
            grain((122, 92, 58), x, y, 5, 95) if x % 3 else (98, 72, 46))
    for face in ("right", "left", "top", "bottom"):
        sh.fill(face, lambda x, y, w, h: (150, 154, 162) if not (face in ("right", "left") and y >= h - 2) else False)
    sc = Box(cv, "guard_scabbard")
    sc.all(lambda face, x, y, w, h: grain((70, 46, 30), x, y, 4, 96))
    sc.row(sc.h - 1, (176, 178, 184))
    sc.row(0, (176, 178, 184))
    return cv


def outfit_smelter():
    """Short sleeves, gauntlets, a scorched leather apron, goggles up."""
    cv = Canvas()
    shirt = (96, 74, 58)
    sh = cloth(shirt, 6, 100)
    coat = coat_to(cv, sh, 13)
    for face in ("right", "left", "back"):                              # apron ties round the waist
        coat.row(9, lambda x: grain((150, 104, 62), x, 9, 4, 101), (face,))
    coat.put("back", 3, 10, (150, 104, 62))
    coat.put("back", 4, 10, (150, 104, 62))
    coat.put("back", 3, 11, (130, 90, 54))
    for x in (2, 5):                                                     # the neck strap
        coat.put("front", x, 0, (150, 104, 62))
        coat.put("front", x, 1, (150, 104, 62))
    coat.fill("top", lambda x, y, w, h: (150, 104, 62) if x in (2, 5) else sh(x, y))
    coat.row(12, lambda x: lit(shirt, 0.75))
    sleeves(cv, sh, 3, cuff=lit(shirt, 0.8))
    for name in ("right_arm", "left_arm"):
        arm = Box(cv, name)
        arm.around(lambda s, y, sw, h, face, x: (grain((74, 50, 34), s, y, 5, 102) if y >= 7 else None))
        arm.row(7, (112, 78, 50))                                       # the gauntlets' cuffs
        arm.row(8, (96, 66, 42))
        arm.fill("bottom", lambda x, y, w, h: (64, 44, 30))
        for y in range(4, 7):                                            # sinew and soot on bare forearms
            arm.put("front", 1, y, None)
    legs(cv, cloth((62, 52, 46), 5, 103), leather((48, 36, 28)), boot_from=9, sole=(26, 22, 20))
    face_paint(cv, [(1, 7, (110, 92, 80)), (6, 6, (104, 88, 78))])
    ap = Box(cv, "smelter_apron")
    la = leather((128, 80, 44), scuff=0.18)

    def apron(x, y, w, h):
        if y == 0 and x in (0, w - 1):
            return False
        c = la(x, y)
        if x in (0, w - 1) or y == h - 1:
            c = lit(c, 0.8)
        if (x, y) in ((2, 6), (3, 9), (5, 4), (4, 12), (6, 10)):
            c = (54, 36, 26)                                            # scorch marks
        if 2 <= x <= 5 and y == 8:
            c = lit((128, 80, 44), 0.7)                                  # the pocket's lip
        return c
    ap.fill("front", apron)
    ap.fill("back", lambda x, y, w, h: False if y == 0 and x in (0, w - 1) else lit(la(x, y), 0.85))
    for face in ("right", "left", "top", "bottom"):
        ap.fill(face, lambda x, y, w, h: lit((128, 80, 44), 0.7))
    ap.put("front", 2, 7, (150, 152, 158))                              # tongs in the pocket
    ap.put("front", 3, 7, (120, 122, 128))
    band = Box(cv, "smelter_band")
    band.all(lambda face, x, y, w, h: grain((58, 40, 28), x, y, 4, 104))
    band.fill("top", lambda x, y, w, h: False if 1 <= x <= 6 and 1 <= y <= 6 else (58, 40, 28))
    band.fill("bottom", lambda x, y, w, h: False if 1 <= x <= 6 and 1 <= y <= 6 else (58, 40, 28))
    band.row(0, (84, 60, 40))
    lens = Box(cv, "smelter_lens_right")
    lens.all(lambda face, x, y, w, h: (182, 142, 62))
    lens.fill("front", lambda x, y, w, h: (150, 220, 210) if (x, y) == (0, 0) else (56, 104, 104))
    return cv


def outfit_fisher():
    """A yellow oilskin coat and sou'wester, tall boots, a creel on its back."""
    cv = Canvas()
    yel = (226, 184, 40)

    def oil(x, y):
        c = lit(yel, 1.08) if noise(x, y, 110) > 0.7 else yel
        return grain(c, x, y, 5, 111)
    coat = coat_to(cv, oil, 16)
    for face in ("front",):
        for y in range(0, 16):
            coat.put(face, 3, y, lit(yel, 0.78))                        # the coat's front edge
        for y in (2, 6, 10):                                            # toggles
            coat.put(face, 4, y, (120, 82, 46))
            coat.put(face, 5, y, (100, 68, 38))
        for x in (1, 2):                                                # pockets
            coat.put(face, x, 11, lit(yel, 0.7))
        for x in (5, 6):
            coat.put(face, x, 11, lit(yel, 0.7))
    coat.row(0, lambda x: lit(yel, 0.82))                              # collar
    coat.row(15, lambda x: lit(yel, 0.75))
    # The creel's strap, across the chest and the back.
    strap = (96, 70, 46)
    for i in range(10):
        coat.put("front", 6 - (i * 6) // 9, i, strap)
        coat.put("back", 1 + (i * 6) // 9, i, strap)
    sleeves(cv, oil, 9, cuff=lit(yel, 0.8))
    navy = (48, 58, 82)
    legs(cv, cloth(navy, 5, 112), lambda x, y: grain((52, 84, 58), x, y, 4, 113), boot_from=5, sole=(30, 40, 30))
    for name in ("right_leg", "left_leg"):
        Box(cv, name).row(5, (78, 116, 84))                             # the boots' rims
    # The sou'wester.
    cr = Box(cv, "fisher_crown")
    crown(cr, oil)
    cr.fill("top", lambda x, y, w, h: lit(yel, 0.82) if (x in (1, 6) or y in (1, 6)) and 1 <= x <= 6 and 1 <= y <= 6 else oil(x, y))
    brim(Box(cv, "fisher_brim"), oil, lit(yel, 0.78))
    flap = Box(cv, "fisher_flap")
    flap.all(lambda face, x, y, w, h: oil(x, y))
    flap.fill("top", lambda x, y, w, h: lit(oil(x, y), 0.9) if y == h - 1 else oil(x, y))
    cr_ = Box(cv, "fisher_creel")
    cr_.all(lambda face, x, y, w, h: wicker()(x, y))
    cr_.row(0, (96, 70, 46))
    cr_.fill("back", lambda x, y, w, h: (196, 200, 210) if (x, y) in ((2, 2), (3, 2)) else None)   # a fish's tail
    lid = Box(cv, "fisher_lid")
    lid.all(lambda face, x, y, w, h: grain((112, 82, 46), x, y, 5, 114))
    lid.fill("top", lambda x, y, w, h: wicker((150, 112, 60), (112, 82, 46))(x, y))
    return cv


def outfit_storekeeper():
    """A white shirt with sleeve garters, a green waistcoat and watch chain,
    pinstripe trousers, spectacles, a derby; a ledger and a quill."""
    cv = Canvas()
    shirt = (234, 230, 218)
    wc = dyed((40, 84, 62), 230)
    sh = cloth(shirt, 4, 120)
    coat = coat_to(cv, sh, 12)
    wcf = cloth(wc, 5, 121)
    coat.fill("front", lambda x, y, w, h: (wcf(x, y) if (x <= 2 or x >= 5) or y >= 4 else None) if y < 11 else None)
    coat.fill("right", lambda x, y, w, h: wcf(x, y) if y < 11 else None)
    coat.fill("left", lambda x, y, w, h: wcf(x, y) if y < 11 else None)
    coat.fill("back", lambda x, y, w, h: grain((148, 132, 110), x, y, 4, 122) if y < 11 else None)   # satin back
    for y in range(4, 11, 2):
        coat.put("front", 4, y, (214, 184, 86))                       # buttons
    coat.put("front", 3, 1, (30, 30, 34))                              # a black tie
    coat.put("front", 4, 1, (30, 30, 34))
    coat.put("front", 3, 2, (30, 30, 34))
    coat.put("front", 3, 3, (40, 40, 46))
    for x, y in ((4, 6), (5, 7), (6, 7)):
        coat.put("front", x, y, (232, 200, 96))                       # watch chain
    coat.put("front", 6, 8, (190, 160, 70))                            # the watch pocket
    coat.row(11, lambda x: grain((36, 32, 30), x, 11, 3, 123))
    sleeves(cv, sh, 9, cuff=(244, 242, 236))
    for name in ("right_arm", "left_arm"):
        Box(cv, name).row(3, (150, 40, 44))                            # sleeve garters
    def pin(s, y):
        return grain((84, 84, 92) if s % 3 else (112, 112, 122), s, y, 3, 124)
    legs(cv, pin, lambda x, y: grain((28, 26, 28), x, y, 3, 125), boot_from=10, sole=(20, 18, 18))
    for name in ("right_leg", "left_leg"):
        Box(cv, name).put("front", 1, 10, (96, 96, 104))                # a polish shine
    # [individual] No spectacles painted on: a storekeeper wears them when its own eyes want them (entity/Keepsakes,
    # tools/folk_looks.py), not because it keeps the stores.
    # The derby.
    dk = (44, 38, 36)
    cr = Box(cv, "storekeeper_crown")
    crown(cr, lambda x, y: grain(dk, x, y, 4, 126), band=(22, 20, 20))
    cr.fill("top", lambda x, y, w, h: lit(dk, 1.3) if (x, y) in ((2, 2), (3, 2), (2, 3)) else grain(dk, x, y, 4, 126))
    brim(Box(cv, "storekeeper_brim"), lambda x, y: grain(dk, x, y, 4, 127), lit(dk, 0.8))
    lg = Box(cv, "storekeeper_ledger")
    lg.all(lambda face, x, y, w, h: grain((116, 40, 40), x, y, 4, 128))
    lg.fill("right", lambda x, y, w, h: (232, 222, 196))               # the pages
    lg.fill("top", lambda x, y, w, h: (232, 222, 196))
    lg.fill("front", lambda x, y, w, h: (214, 184, 86) if (x in (0, w - 1) and y in (0, h - 1)) else None)
    q = Box(cv, "storekeeper_quill")
    q.all(lambda face, x, y, w, h: (240, 240, 236) if y < 3 else ((40, 36, 34) if y == 4 else (200, 196, 186)))
    return cv


def outfit_hauler():
    """A canvas jacket with pockets, a scarf, puttees, a tweed flat cap; a big
    pack with a bedroll and a pan."""
    cv = Canvas()
    jk = (96, 106, 78)
    jf = cloth(jk, 6, 130)
    coat = coat_to(cv, jf, 14)
    for y in range(0, 14):
        coat.put("front", 3, y, lit(jk, 0.8))                          # the jacket's opening
    for (x, y) in ((1, 4), (2, 4), (5, 4), (6, 4), (1, 9), (2, 9), (5, 9), (6, 9)):
        coat.put("front", x, y, lit(jk, 0.72))                          # four pockets
    for (x, y) in ((1, 5), (2, 5), (5, 5), (6, 5), (1, 10), (2, 10), (5, 10), (6, 10)):
        coat.put("front", x, y, lit(jk, 0.88))
    strap = (102, 70, 42)
    for y in range(0, 12):                                               # the pack's straps
        coat.put("front", 1, y, strap) if y < 3 else None
        coat.put("front", 6, y, strap) if y < 3 else None
    belt(coat, 12, strap=(66, 46, 30), buckle=(170, 172, 176))
    scarf = dyed((196, 92, 44), 236)
    coat.row(0, lambda x: grain(scarf, x, 0, 6, 131))
    for y in range(1, 6):
        coat.put("front", 2, y, grain(scarf, 2, y, 6, 131) if y < 5 else lit(scarf, 0.8))   # the scarf's end
    sleeves(cv, jf, 9, cuff=lit(jk, 0.8))
    legs(cv, cloth((104, 80, 56), 6, 132), leather((60, 44, 32)), boot_from=10, sole=(30, 24, 20))
    for name in ("right_leg", "left_leg"):
        leg = Box(cv, name)
        for y in range(5, 10):
            leg.row(y, lambda x, y=y: (196, 184, 156) if (x + y) % 3 else (170, 158, 132))  # puttees
    rl = Box(cv, "right_leg")
    for (x, y) in ((1, 2), (2, 2), (1, 3), (2, 3)):
        rl.put("front", x, y, (138, 104, 70))                           # a patch
    # The flat cap.
    tw = tweed()
    cap = Box(cv, "hauler_cap")
    crown(cap, tw)
    cap.fill("top", lambda x, y, w, h: lit(tw(x, y), 0.85) if y >= 6 else tw(x, y))
    visor = Box(cv, "hauler_visor")
    visor.all(lambda face, x, y, w, h: lit(tw(x, y), 0.92))
    visor.fill("bottom", lambda x, y, w, h: lit((118, 94, 70), 0.6))
    # The pack.
    can = (156, 130, 88)
    pk = Box(cv, "hauler_pack")
    pk.all(lambda face, x, y, w, h: grain(can, x, y, 6, 133))
    lf = leather((112, 76, 44))

    def flap(x, y, w, h):
        if y < 5:
            return lf(x, y)
        if y == 5:
            return lit((112, 76, 44), 0.7)
        if y in (7, 8) and x in (1, 2, 5, 6):
            return lit(can, 0.78)                                        # side-by-side pockets
        return None
    pk.fill("back", flap)
    for x in (2, 5):
        pk.put("back", x, 5, (176, 178, 182))                            # buckles
        pk.put("back", x, 4, (140, 142, 148))
    pk.fill("top", lambda x, y, w, h: lf(x, y))
    for face in ("right", "left"):
        pk.fill(face, lambda x, y, w, h: lit(can, 0.82) if 5 <= y <= 8 and 0 < x < 3 else None)
    roll = Box(cv, "hauler_roll")
    roll.all(lambda face, x, y, w, h: grain((168, 44, 40) if x % 4 < 2 else (226, 214, 188), x, y, 5, 134))
    for face in ("right", "left"):
        roll.fill(face, lambda x, y, w, h: (196, 54, 48) if (x + y) % 2 else (226, 214, 188))
    for face in ("top", "front", "back", "bottom"):
        w, h = roll.size(face)
        for x in (2, w - 3):
            for y in range(h):
                roll.put(face, x, y, (90, 62, 38))                       # its straps
    pan = Box(cv, "hauler_pan")
    pan.all(lambda face, x, y, w, h: (54, 54, 58))
    pan.fill("left", lambda x, y, w, h: (74, 74, 80) if (x, y) not in ((0, 0), (2, 0), (0, 3), (2, 3)) else False)
    pan.fill("right", lambda x, y, w, h: (40, 40, 44) if (x, y) not in ((0, 0), (2, 0), (0, 3), (2, 3)) else False)
    return cv


def apron_box(cv, name, base, scorch=(), pocket_row=None, stripes=None):
    """A bib apron, front and back: edges darker, scorch marks, an optional pocket."""
    ap = Box(cv, name)
    la = cloth(base, 6, 140) if stripes is None else (lambda x, y: grain(base if x % 2 == 0 else stripes, x, y, 3, 141))

    def front(x, y, w, h):
        if y == 0 and x in (0, w - 1):
            return False
        c = la(x, y)
        if x in (0, w - 1) or y == h - 1:
            c = lit(c, 0.82)
        if (x, y) in scorch:
            c = lit(base, 0.45)
        if pocket_row is not None and 2 <= x <= w - 3 and y == pocket_row:
            c = lit(base, 0.7)
        return c
    ap.fill("front", front)
    ap.fill("back", lambda x, y, w, h: False if y == 0 and x in (0, w - 1) else lit(la(x, y), 0.85))
    for face in ("right", "left", "top", "bottom"):
        ap.fill(face, lambda x, y, w, h: lit(base, 0.72))
    return ap


def outfit_blacksmith():
    """Bare forearms black with soot, a sleeveless shirt, a long scorched leather
    apron, a red headscarf, heavy boots; a hammer at its hip."""
    cv = Canvas()
    shirt = (70, 62, 58)
    sh = cloth(shirt, 6, 150)
    coat_to(cv, sh, 12)
    sleeves(cv, sh, 2, cuff=lit(shirt, 0.8))
    for name in ("right_arm", "left_arm"):
        arm = Box(cv, name)
        for y in range(5, 11):
            arm.put("front", (y * 3) % 4, y, (92, 74, 64))               # soot on the forearms
        arm.around(lambda s, y, sw, h, face, x: grain((52, 40, 32), s, y, 4, 151) if y >= 9 else None)
    legs(cv, cloth((58, 50, 46), 5, 152), leather((40, 30, 24)), boot_from=8, sole=(22, 18, 16))
    apron_box(cv, "blacksmith_apron", (88, 58, 36), scorch=((2, 5), (5, 9), (3, 12), (6, 3), (1, 10)), pocket_row=7)
    sc = (162, 40, 36)
    scarf = Box(cv, "blacksmith_scarf")
    scarf.all(lambda face, x, y, w, h: grain(sc, x, y, 7, 153) if (x + y) % 5 else lit(sc, 0.8))
    scarf.fill("bottom", lambda x, y, w, h: False if 1 <= x <= 6 and 1 <= y <= 6 else lit(sc, 0.7))
    Box(cv, "blacksmith_knot").all(lambda face, x, y, w, h: lit(sc, 0.85))
    hm = Box(cv, "blacksmith_hammer")
    hm.all(lambda face, x, y, w, h: grain((112, 80, 50), x, y, 5, 154))
    head = Canvas()
    # the hammer's iron head is the second box: paint its faces on the same picture
    fs = faces(104, 0, 2, 2, 3)
    for face, (fx, fy, fw, fh) in fs.items():
        for yy in range(fh):
            for xx in range(fw):
                cv.set(fx + xx, fy + yy, grain((92, 94, 102), xx, yy, 6, 155))
    face_paint(cv, [(1, 8, (84, 70, 62)), (6, 7, (80, 66, 58))])
    return cv


def outfit_tailor():
    """A crisp shirt, a waistcoat in its own colour, neat trousers, a beret;
    a yellow tape measure round its neck and a spool at its belt."""
    cv = Canvas()
    shirt = (238, 234, 222)
    sh = cloth(shirt, 4, 160)
    coat = coat_to(cv, sh, 12)
    wc = dyed((122, 44, 66), 228)
    wcf = cloth(wc, 5, 161)
    coat.fill("front", lambda x, y, w, h: (wcf(x, y) if (x <= 2 or x >= 5) or y >= 3 else None) if y < 11 else None)
    coat.fill("right", lambda x, y, w, h: wcf(x, y) if y < 11 else None)
    coat.fill("left", lambda x, y, w, h: wcf(x, y) if y < 11 else None)
    coat.fill("back", lambda x, y, w, h: wcf(x, y) if y < 11 else None)
    for y in range(3, 11, 2):
        coat.put("front", 4, y, (226, 204, 120))                         # buttons
    for (x, y) in ((1, 6), (2, 6), (1, 7)):
        coat.put("front", x, y, (214, 60, 60))                           # a pincushion's pins
    coat.row(11, lambda x: grain((44, 40, 46), x, 11, 3, 162))
    sleeves(cv, sh, 9, cuff=(246, 244, 238))
    legs(cv, cloth((70, 68, 80), 4, 163), lambda x, y: grain((36, 30, 30), x, y, 3, 164), boot_from=10, sole=(22, 18, 18))
    be = dyed((58, 58, 110), 222)
    bt = Box(cv, "tailor_beret")
    bt.all(lambda face, x, y, w, h: grain(be, x, y, 6, 165))
    bt.fill("top", lambda x, y, w, h: lit(be, 0.85) if (x, y) == (4, 4) else grain(be, x, y, 6, 165))
    tape = Box(cv, "tailor_tape")
    tape.all(lambda face, x, y, w, h: (232, 196, 70) if x % 3 else (60, 50, 30))
    tape.fill("top", lambda x, y, w, h: False if 1 <= x <= w - 2 and 1 <= y <= h - 2 else (232, 196, 70))
    tape.fill("bottom", lambda x, y, w, h: False if 1 <= x <= w - 2 and 1 <= y <= h - 2 else (210, 176, 62))
    sp = Box(cv, "tailor_spool")
    sp.all(lambda face, x, y, w, h: (150, 110, 70) if y in (0, h - 1) else (70, 120, 190))
    return cv


def outfit_beekeeper():
    """A white bee-suit, gloves and boots, a wide hat with a net veil to the
    shoulders; a smoker at its belt."""
    cv = Canvas()
    suit = (232, 230, 222)
    sf = cloth(suit, 5, 170)
    coat = coat_to(cv, sf, 18)
    for y in (6, 12):
        coat.row(y, lambda x: lit(suit, 0.9))                            # seams
    coat.put("front", 3, 4, (196, 170, 70))                              # zip pull
    for y in range(0, 17):
        coat.put("front", 4, y, lit(suit, 0.86))                         # the zip
    sleeves(cv, sf, 11, cuff=lit(suit, 0.88))
    for name in ("right_arm", "left_arm"):
        arm = Box(cv, name)
        arm.around(lambda s, y, sw, h, face, x: grain((214, 186, 120), s, y, 4, 171) if y >= 8 else None)   # gloves
    legs(cv, sf, leather((210, 206, 196)), boot_from=9, sole=(60, 58, 54))
    st = (226, 206, 150)
    cr = Box(cv, "beekeeper_crown")
    crown(cr, lambda x, y: grain(st, x, y, 5, 172), band=(70, 64, 54))
    brim(Box(cv, "beekeeper_brim"), lambda x, y: grain(st, x, y, 5, 173), lit(st, 0.82))
    veil = Box(cv, "beekeeper_veil")
    net = (34, 34, 30)

    def mesh(x, y, w, h):
        return net if (x % 2 == 0 or y % 2 == 0) else False
    for face in ("right", "front", "left", "back"):
        veil.fill(face, mesh)
    veil.fill("top", lambda x, y, w, h: False)
    veil.fill("bottom", lambda x, y, w, h: False)
    sm = Box(cv, "beekeeper_smoker")
    sm.all(lambda face, x, y, w, h: (150, 150, 158) if y > 0 else (90, 90, 96))
    sm.fill("top", lambda x, y, w, h: (40, 40, 42))
    return cv


def outfit_brewer():
    """A homespun shirt with rolled sleeves, a stained apron, a soft cap; a belt
    of three little vials, green, red and blue."""
    cv = Canvas()
    shirt = (190, 170, 130)
    sh = cloth(shirt, 6, 180)
    coat = coat_to(cv, sh, 13)
    belt(coat, 11, strap=(78, 52, 32), buckle=(190, 160, 80))
    sleeves(cv, sh, 6, cuff=lit(shirt, 0.82))
    legs(cv, cloth((80, 70, 60), 5, 181), leather((70, 48, 30)), boot_from=9, sole=(30, 24, 20))
    apron_box(cv, "brewer_apron", (176, 166, 140), scorch=((2, 4), (5, 8), (3, 10)), pocket_row=6)
    ap = Box(cv, "brewer_apron")
    for (x, y, c) in ((2, 4, (90, 150, 70)), (5, 8, (150, 60, 90)), (3, 10, (70, 90, 160))):
        ap.put("front", x, y, c)                                         # stains of the day's brews
    cp = (96, 70, 110)
    Box(cv, "brewer_cap").all(lambda face, x, y, w, h: grain(cp, x, y, 6, 182))
    for name, glass in (("brewer_vial_a", (90, 200, 90)), ("brewer_vial_b", (210, 60, 70)), ("brewer_vial_c", (80, 120, 230))):
        v = Box(cv, name)
        v.all(lambda face, x, y, w, h, glass=glass: (150, 110, 70) if y == 0 else glass)
    return cv


def outfit_enchanter():
    """A long robe to the ankles, deep blue with silver stars, a tall pointed hat;
    a book of spells at its belt."""
    cv = Canvas()
    robe = dyed((52, 44, 120), 220)
    rf = cloth(robe, 6, 190)

    def starry(x, y):
        if noise(x, y, 191) > 0.86:
            return (214, 216, 236)
        return rf(x, y)
    coat = coat_to(cv, starry, 18)
    coat.row(17, lambda x: (196, 170, 80))                               # gold hem
    belt(coat, 9, strap=(150, 120, 50), buckle=(220, 200, 120))
    sleeves(cv, starry, 11, cuff=(196, 170, 80))
    legs(cv, starry, leather((50, 40, 60)), boot_from=11, sole=(24, 20, 30))
    base = Box(cv, "enchanter_hat_base")
    base.all(lambda face, x, y, w, h: starry(x, y))
    base.fill("bottom", lambda x, y, w, h: False if 2 <= x <= 7 and 2 <= y <= 7 else lit(robe, 0.7))
    Box(cv, "enchanter_hat_mid").all(lambda face, x, y, w, h: starry(x + 3, y + 5))
    tip = Box(cv, "enchanter_hat_tip")
    tip.all(lambda face, x, y, w, h: (214, 216, 236) if (face == "top") else starry(x + 7, y + 9))
    bk = Box(cv, "enchanter_book")
    bk.all(lambda face, x, y, w, h: grain((110, 40, 90), x, y, 4, 192))
    bk.fill("right", lambda x, y, w, h: (232, 222, 196))
    bk.fill("front", lambda x, y, w, h: (220, 196, 90) if (x, y) in ((1, 1), (1, 2)) else None)
    face_paint(cv, [])
    return cv


def outfit_cook():
    """Chef's whites: a double-breasted jacket, check trousers, a striped apron and
    a tall toque; a wooden spoon at its belt."""
    cv = Canvas()
    white = (240, 238, 232)
    wf = cloth(white, 4, 200)
    coat = coat_to(cv, wf, 12)
    for (x, y) in ((2, 2), (5, 2), (2, 5), (5, 5), (2, 8), (5, 8)):
        coat.put("front", x, y, (210, 200, 180))                         # its two rows of buttons
    coat.row(0, lambda x: (196, 52, 48))                                 # a red neckerchief
    sleeves(cv, wf, 9, cuff=(226, 224, 218))

    def check(x, y):
        return (60, 60, 64) if (x // 2 + y // 2) % 2 else (226, 224, 220)
    legs(cv, check, lambda x, y: grain((34, 30, 30), x, y, 3, 201), boot_from=10, sole=(20, 18, 18))
    apron_box(cv, "cook_apron", (232, 228, 218), stripes=(70, 100, 160))
    Box(cv, "cook_band").all(lambda face, x, y, w, h: grain(white, x, y, 3, 202))
    puff = Box(cv, "cook_puff")
    puff.all(lambda face, x, y, w, h: lit(white, 0.92) if (x % 3 == 0 and face != "top") else grain(white, x, y, 3, 203))
    Box(cv, "cook_spoon").all(lambda face, x, y, w, h: grain((170, 126, 76), x, y, 5, 204))
    return cv


def outfit_shopkeeper():
    """Shirtsleeves with garters, a bow tie, a green-and-white striped apron, a cap
    with a green visor; a purse of coin at its belt."""
    cv = Canvas()
    shirt = (230, 226, 210)
    sh = cloth(shirt, 4, 210)
    coat = coat_to(cv, sh, 12)
    coat.put("front", 3, 1, (150, 40, 44))                               # the bow tie
    coat.put("front", 4, 1, (150, 40, 44))
    coat.put("front", 2, 1, (120, 32, 36))
    coat.put("front", 5, 1, (120, 32, 36))
    coat.row(11, lambda x: grain((60, 46, 36), x, 11, 3, 211))
    sleeves(cv, sh, 9, cuff=(240, 238, 230))
    for name in ("right_arm", "left_arm"):
        Box(cv, name).row(3, (60, 120, 70))                              # sleeve garters
    legs(cv, cloth((78, 70, 62), 4, 212), lambda x, y: grain((50, 36, 26), x, y, 3, 213), boot_from=10, sole=(26, 20, 16))
    green = dyed((48, 120, 72), 232)
    apron_box(cv, "shopkeeper_apron", green, stripes=(232, 230, 220))
    cap = Box(cv, "shopkeeper_cap")
    crown(cap, lambda x, y: grain((232, 228, 216), x, y, 3, 214), band=(48, 120, 72))
    vs = Box(cv, "shopkeeper_visor")
    vs.all(lambda face, x, y, w, h: (60, 150, 90))
    vs.fill("bottom", lambda x, y, w, h: (40, 110, 66))
    pu = Box(cv, "shopkeeper_pouch")
    pu.all(lambda face, x, y, w, h: grain((120, 84, 48), x, y, 4, 215))
    pu.fill("front", lambda x, y, w, h: (214, 180, 70) if (x, y) == (1, 1) else None)
    return cv


def outfit_scout():
    """A ranger: a laced leather jerkin, green sleeves, breeches and long boots; a
    hooded green cape to the knee; a map case at its hip, a brass spyglass at its
    belt, and a red feather stuck in its hood."""
    cv = Canvas()
    jerkin = (112, 84, 54)
    jf = leather(jerkin)
    coat = coat_to(cv, jf, 13)
    for y in range(0, 12):
        coat.put("front", 3, y, lit(jerkin, 0.72))                       # the jerkin's laced front
        if y % 2 == 1:
            coat.put("front", 4, y, (204, 184, 140))                     # its lacing
    belt(coat, 10, strap=(58, 40, 26), buckle=(196, 164, 84))
    green = (60, 94, 54)
    gf = cloth(green, 7, 240)
    sleeves(cv, gf, 9, cuff=lit(jerkin, 0.85))
    legs(cv, cloth((96, 86, 66), 5, 241), leather((70, 48, 30)), boot_from=5, sole=(28, 22, 18))
    for name in ("right_leg", "left_leg"):
        Box(cv, name).row(5, (52, 36, 22))                               # the boot tops, turned down

    def hood_f(face, x, y, w, h):
        if face == "bottom":
            return False
        if face == "front":
            if 1 <= x <= w - 2 and 2 <= y <= h - 1:
                return False                                             # the face shows through
            return lit(green, 0.66)                                      # the rim, in shadow
        return lit(gf(x, y), 0.92) if (x + y) % 5 == 0 else gf(x, y)
    Box(cv, "scout_hood").all(hood_f)
    cape = Box(cv, "scout_cape")
    cape.all(lambda face, x, y, w, h: lit(green, 0.74) if y >= h - 1 else (lit(gf(x, y), 0.88) if x % 3 == 0 else gf(x, y)))
    sat = Box(cv, "scout_satchel")
    sat.all(lambda face, x, y, w, h: grain((132, 94, 58), x, y, 5, 242))
    sat.fill("top", lambda x, y, w, h: (92, 64, 40))
    sat.fill("left", lambda x, y, w, h: (196, 164, 84) if (x, y) == (1, 1) else None)   # its buckle
    spy = Box(cv, "scout_spyglass")
    spy.all(lambda face, x, y, w, h: (214, 170, 82) if y in (0, h - 1) else (168, 120, 54))
    fe = Box(cv, "scout_feather")
    fe.all(lambda face, x, y, w, h: (206, 58, 44) if y < 3 else (236, 226, 210))
    return cv


def outfit_hunter():
    """A hunter: a weathered leather jerkin and breeches, soft boots, a hood mottled in the
    browns and greens of the wood, a fur mantle round its shoulders, a quiver of arrows
    across its back and a knife at its hip."""
    cv = Canvas()
    jerkin = (104, 76, 48)
    jf = leather(jerkin)
    coat = coat_to(cv, jf, 12)
    for y in range(0, 12):
        if y % 3 == 0:
            coat.put("front", 2, y, lit(jerkin, 0.7))                    # stitched seams
            coat.put("front", 5, y, lit(jerkin, 0.7))
    belt(coat, 9, strap=(54, 38, 24), buckle=(150, 130, 96))
    moss = (78, 88, 52)
    sleeves(cv, cloth(moss, 6, 250), 9, cuff=lit(jerkin, 0.8))
    legs(cv, leather((88, 66, 44)), leather((62, 44, 28)), boot_from=6, sole=(30, 22, 16))

    def mottle(x, y):
        k = (x * 7 + y * 13 + (x * y) % 5) % 9
        return [(84, 92, 54), (96, 78, 50), (70, 80, 46), (110, 92, 60)][k % 4] if k > 2 else (76, 66, 42)

    def hood_f(face, x, y, w, h):
        if face == "bottom":
            return False
        if face == "front":
            if 1 <= x <= w - 2 and 2 <= y <= h - 1:
                return False                                             # the face shows through
            return lit(mottle(x, y), 0.7)
        return mottle(x, y)
    Box(cv, "hunter_hood").all(hood_f)
    fur = Box(cv, "hunter_mantle")
    fur.all(lambda face, x, y, w, h: lit((150, 118, 82), 0.8 + ((x * 5 + y * 3) % 4) * 0.08) if face != "bottom" else (110, 84, 58))
    q = Box(cv, "hunter_quiver")
    q.all(lambda face, x, y, w, h: grain((120, 80, 44), x, y, 4, 251) if y not in (1, h - 2) else (70, 48, 28))
    fl = Box(cv, "hunter_fletch")
    fl.all(lambda face, x, y, w, h: (226, 222, 210) if y < 2 else (120, 96, 62))
    kn = Box(cv, "hunter_knife")
    kn.all(lambda face, x, y, w, h: (190, 196, 204) if y >= 2 else (84, 60, 38))
    return cv


def outfit_fletcher():
    """[fletcher] A fletcher: a tunic of Lincoln green to the hip under a tan leather bib apron, its pocket bristling
    with spare feathers; a leather strap across the chest to the quiver on its back; brown breeches and soft boots;
    a green felt cap with a peak, and a long white goose feather in its band. The quiver is stitched leather with a
    brass band, its arrows' red and white flights showing at the top."""
    cv = Canvas()
    green = (74, 102, 50)
    tunic = cloth(green, 6, 400)
    coat = coat_to(cv, tunic, 12)
    for y in range(0, 12):
        if y % 4 == 1:
            coat.put("front", 3, y, lit(green, 0.8))                     # the lacing at the neck and down the front
    strap = (92, 62, 36)
    for i in range(7):                                                    # the quiver's strap, right shoulder to left hip
        coat.put("front", 1 + i, i, strap)
        coat.put("front", 1 + i, i + 1, lit(strap, 0.8))
        coat.put("back", 6 - i, i, strap)
    belt(coat, 10, strap=(70, 48, 30), buckle=(196, 166, 80))
    sleeves(cv, tunic, 7, cuff=lit(green, 0.78))
    for name in ("right_arm", "left_arm"):
        arm = Box(cv, name)
        arm.around(lambda s, y, sw, h, face, x: grain((128, 96, 62), s, y, 4, 401) if y >= 10 else None)   # bracers
    legs(cv, leather((98, 72, 46)), leather((64, 46, 30)), boot_from=8, sole=(34, 26, 20))
    for name in ("right_leg", "left_leg"):
        Box(cv, name).row(8, (78, 56, 36))                                # the boots' turned tops
    # The apron: tan leather, a darker stitched hem, a pocket with feathers in it.
    tan = (178, 130, 80)
    ap = Box(cv, "fletcher_apron")

    def apron(x, y, w, h):
        if y == 0 and x in (0, w - 1):
            return False
        c = leather(tan, 0.1)(x, y)
        if x in (0, w - 1) or y == h - 1:
            c = lit(tan, 0.78)
        if 1 <= x <= w - 2 and y == 7:
            c = lit(tan, 0.68)                                            # the pocket's top
        if y in (5, 6) and x in (2, 4):
            c = (238, 234, 222)                                           # feathers in the pocket
        if y == 6 and x == 3:
            c = (176, 172, 160)
        return c
    ap.fill("front", apron)
    ap.fill("back", lambda x, y, w, h: False if y == 0 and x in (0, w - 1) else lit(tan, 0.7))
    for face in ("right", "left", "top", "bottom"):
        ap.fill(face, lambda x, y, w, h: lit(tan, 0.66))
    # The cap: green felt, a brown band, and its peak.
    felt = (64, 100, 44)
    cap = Box(cv, "fletcher_cap")
    crown(cap, lambda x, y: grain(felt if (x + y) % 5 else lit(felt, 0.86), x, y, 6, 402), band=(96, 66, 40), band_rows=1)
    cap.fill("top", lambda x, y, w, h: grain(lit(felt, 1.08) if 2 <= x <= 5 and 2 <= y <= 5 else felt, x, y, 6, 403))
    cap.fill("bottom", lambda x, y, w, h: False if 1 <= x <= 6 and 1 <= y <= 6 else lit(felt, 0.7))
    pk = Box(cv, "fletcher_peak")
    pk.all(lambda face, x, y, w, h: lit(felt, 0.82) if face != "top" else grain(felt, x, y, 5, 404))
    # The feather: white vanes, a grey-brown tip, the quill at its root.
    fe = Box(cv, "fletcher_feather")
    fe.all(lambda face, x, y, w, h: (226, 222, 210) if y >= 2 and y < h - 1 else ((128, 112, 92) if y < 2 else (200, 186, 150)))
    # The quiver: stitched leather, a brass band at its mouth, its flights red and white.
    q = Box(cv, "fletcher_quiver")
    qc = (118, 78, 42)
    q.all(lambda face, x, y, w, h: (190, 158, 72) if y == 1 else ((84, 56, 30) if y == h - 2 or (face in ("front", "back") and x == 1 and y % 2 == 0)
                                                             else grain(qc, x, y, 5, 405)))
    fl = Box(cv, "fletcher_fletch")
    fl.all(lambda face, x, y, w, h: ((188, 42, 38) if x % 2 == 0 else (238, 234, 224)) if y < 2 else (126, 98, 64))
    return cv


def outfit_golemkeeper():
    """[golems] A golem keeper: a charcoal work shirt, its sleeves rolled to the elbow over forearms grey with iron dust;
    a heavy dark leather apron to the knee, its bib and hem studded with iron rivets; a pumpkin-orange knitted scarf
    wound round its neck, one end hanging down its chest; a riveted leather skullcap; dark breeches and heavy boots
    with iron toecaps; and at its hip the shears it carves the golems' pumpkins with."""
    cv = Canvas()
    shirt = (72, 72, 78)
    sh = cloth(shirt, 6, 420)
    coat_to(cv, sh, 12)
    sleeves(cv, sh, 4, cuff=lit(shirt, 0.75))
    for name in ("right_arm", "left_arm"):
        arm = Box(cv, name)
        for y in range(6, 10):
            arm.put("front", (y * 3) % 4, y, (150, 140, 132))             # iron dust on the forearms
        arm.around(lambda s, y, sw, h, face, x: grain((58, 44, 34), s, y, 4, 421) if y >= 10 else None)   # gauntlets
    legs(cv, cloth((62, 56, 50), 5, 422), leather((38, 32, 28)), boot_from=8, sole=(22, 20, 18))
    for name in ("right_leg", "left_leg"):
        leg = Box(cv, name)
        for x in range(4):
            leg.put("front", x, 11, (156, 158, 164) if x in (1, 2) else (128, 130, 136))   # iron toecaps
    face_paint(cv, [(1, 8, (120, 112, 106)), (6, 7, (116, 108, 100))])
    rivet, rivet_dark = (186, 190, 196), (120, 124, 130)
    hide = (82, 56, 36)
    ap = Box(cv, "golemkeeper_apron")

    def apron(x, y, w, h):
        if y == 0 and x in (0, w - 1):
            return False
        c = leather(hide, 0.14)(x, y)
        if x in (0, w - 1) or y == h - 1:
            c = lit(hide, 0.74)
        if y in (1, h - 2) and x % 2 == 1 and 0 < x < w - 1:
            c = rivet                                                     # a row of rivets at the bib and the hem
        if x in (1, w - 2) and y % 3 == 0 and 2 < y < h - 2:
            c = rivet_dark                                                # down the sides
        if y == 8 and 2 <= x <= w - 3:
            c = lit(hide, 0.6)                                            # the pocket's seam
        if (x, y) in ((3, 4), (4, 4), (3, 5), (4, 5)):
            c = (150, 152, 158) if (x + y) % 2 else (176, 178, 184)       # an iron patch over the heart
        return c
    ap.fill("front", apron)
    ap.fill("back", lambda x, y, w, h: False if y == 0 and x in (0, w - 1) else lit(hide, 0.68))
    for face in ("right", "left", "top", "bottom"):
        ap.fill(face, lambda x, y, w, h: lit(hide, 0.62))
    orange, fold = (228, 126, 30), (184, 90, 18)
    sc = Box(cv, "golemkeeper_scarf")
    sc.all(lambda face, x, y, w, h: grain(orange if x % 2 == 0 else lit(orange, 0.9), x, y, 5, 423) if (x + y) % 4 else fold)
    sc.fill("bottom", lambda x, y, w, h: False if 1 <= x <= w - 2 and 1 <= y <= h - 2 else fold)
    sc.fill("top", lambda x, y, w, h: False if 1 <= x <= w - 2 and 1 <= y <= h - 2 else orange)
    end = Box(cv, "golemkeeper_scarf_end")
    end.all(lambda face, x, y, w, h: (fold if y == h - 1 else (lit(orange, 1.05) if (x + y) % 3 else orange)))
    end.fill("front", lambda x, y, w, h: (246, 214, 150) if y == h - 1 else (orange if (x + y) % 2 else lit(orange, 0.9)))   # the fringe
    cap = Box(cv, "golemkeeper_cap")
    capc = (66, 48, 34)
    crown(cap, lambda x, y: grain(capc, x, y, 5, 424))
    cap.row(1, lambda x: rivet_dark if x % 3 == 1 else lit(capc, 0.8))
    cap.fill("top", lambda x, y, w, h: rivet if (x, y) in ((3, 3), (4, 4)) else grain(lit(capc, 1.1), x, y, 5, 425))
    cap.fill("bottom", lambda x, y, w, h: False if 1 <= x <= 6 and 1 <= y <= 6 else lit(capc, 0.7))
    shears = Box(cv, "golemkeeper_shears")
    shears.all(lambda face, x, y, w, h: (196, 200, 206) if y < 2 else (84, 60, 40))
    return cv


OUTFITS = {
    "none": outfit_none,
    "farmer": outfit_farmer,
    "lumberjack": outfit_lumberjack,
    "miner": outfit_miner,
    "rancher": outfit_rancher,
    "guard": outfit_guard,
    "smelter": outfit_smelter,
    "fisher": outfit_fisher,
    "storekeeper": outfit_storekeeper,
    "hauler": outfit_hauler,
    "blacksmith": outfit_blacksmith,
    "tailor": outfit_tailor,
    "beekeeper": outfit_beekeeper,
    "brewer": outfit_brewer,
    "enchanter": outfit_enchanter,
    "cook": outfit_cook,
    "shopkeeper": outfit_shopkeeper,
    "scout": outfit_scout,
    "hunter": outfit_hunter,
    "cavedweller": outfit_cavedweller,                                    # [caves]
    "fletcher": outfit_fletcher,                                          # [fletcher]
    "golemkeeper": outfit_golemkeeper,                                    # [golems]
    "fireworks": outfit_fireworks,                                        # [fireworks]
    "cartographer": outfit_cartographer,                                  # [cartographer]
    "emerald": outfit_emerald,                                            # [emerald]
    "netherrunner": outfit_netherrunner,                                  # [nether]
}
def emerald_glow():
    """[emerald] The emerald trader's little lantern, lit whatever the light round it."""
    cv = Canvas()
    lamp = Box(cv, "emerald_lamp")
    for face in Box.SIDES:
        lamp.fill(face, lambda x, y, w, h: (255, 214, 120) if 0 < y < h - 1 else None)
    return cv


GLOWS = {"miner": miner_glow, "cavedweller": cavedweller_glow, "emerald": emerald_glow, "netherrunner": netherrunner_glow}
DYED = ("none", "farmer", "lumberjack", "rancher", "guard", "storekeeper", "hauler",
        "tailor", "enchanter", "shopkeeper")


# ------------------------------------------------------------------------ Java

def fnum(v):
    s = ("%.4f" % v).rstrip("0").rstrip(".")
    if s in ("-0", ""):
        s = "0"
    return s + ".0F" if "." not in s else s + "F"


def java_geometry():
    out = []
    out.append("        MeshDefinition mesh = new MeshDefinition();")
    out.append("        PartDefinition root = mesh.getRoot();")
    made = {None: "root"}
    for name, parent, pivot, rot, cubes, worn in PARTS:
        var = re.sub(r"_(\w)", lambda m: m.group(1).upper(), name)
        builder = "CubeListBuilder.create()"
        for (u, v, x, y, z, w, h, d, g) in cubes:
            builder += ".texOffs(%d, %d).addBox(%s, %s, %s, %s, %s, %s%s)" % (
                u, v, fnum(x), fnum(y), fnum(z), fnum(w), fnum(h), fnum(d),
                (", new CubeDeformation(%s)" % fnum(g)) if g else "")
        if any(rot):
            pose = "PartPose.offsetAndRotation(%s, %s, %s, %s, %s, %s)" % tuple(fnum(k) for k in pivot + rot)
        elif any(pivot):
            pose = "PartPose.offset(%s, %s, %s)" % tuple(fnum(k) for k in pivot)
        else:
            pose = "PartPose.ZERO"
        has_children = any(p[1] == name for p in PARTS)
        prefix = ("PartDefinition %s = " % var) if has_children else ""
        out.append("        %s%s.addOrReplaceChild(\"%s\", %s, %s);" % (prefix, made[parent], name, builder, pose))
        made[name] = var
    out.append("        return LayerDefinition.create(mesh, %d, %d);" % (SIZE, SIZE))
    return "\n".join(out)


def java_wearers():
    lines = []
    for name, parent, pivot, rot, cubes, worn in PARTS:
        if worn in ("all",):
            continue
        lines.append("        {\"%s\", \"%s\", \"%s\"}," % (name, parent or "root", worn))
    return "\n".join(lines)


def java_looks():
    beards = ", ".join("true" if s[4] else "false" for s in SKINS)
    return "    private static final boolean[] BEARDED = {%s};" % beards


def splice(text, tag, body):
    """Replace whatever stands between the BEGIN and END marker lines."""
    pat = re.compile(r"(// BEGIN GENERATED %s\n).*?\n?([ \t]*// END GENERATED %s)" % (tag, tag), re.S)
    if not pat.search(text):
        raise SystemExit("no GENERATED %s block" % tag)
    return pat.sub(lambda m: m.group(1) + body + "\n" + m.group(2), text, count=1)


def main():
    check = "--check" in sys.argv
    os.makedirs(TEX_DIR, exist_ok=True)
    pictures = {}
    for i, s in enumerate(SKINS):
        pictures["skin_%d.png" % i] = paint_skin(*s).png()
    for trade, fn in OUTFITS.items():
        plain = fn()
        pictures["%s.png" % trade] = plain.png()
        if trade in DYED:
            DYE_MODE[0] = True
            try:
                grey = fn()
            finally:
                DYE_MODE[0] = False
            mask = Canvas()
            for y in range(SIZE):
                for x in range(SIZE):
                    if grey.get(x, y) != plain.get(x, y):
                        mask.set(x, y, grey.get(x, y))
            pictures["%s_dye.png" % trade] = mask.png()
    for trade, fn in GLOWS.items():
        pictures["%s_glow.png" % trade] = fn().png()
    changed = []
    for name, data in pictures.items():
        path = os.path.join(TEX_DIR, name)
        old = open(path, "rb").read() if os.path.exists(path) else None
        if old != data:
            changed.append(name)
            if not check:
                with open(path, "wb") as fh:
                    fh.write(data)
    for path, blocks in ((JAVA, (("GEOMETRY", java_geometry()), ("WEARERS", java_wearers()))),
                         (LOOKS_JAVA, (("LOOKS", java_looks()),))):
        text = open(path).read()
        new = text
        for tag, body in blocks:
            new = splice(new, tag, body)
        if new != text:
            changed.append(os.path.basename(path))
            if not check:
                open(path, "w").write(new)
    print(("would change: " if check else "wrote: ") + (", ".join(changed) if changed else "nothing"))


if __name__ == "__main__":
    main()
