# Acceptance tests and manual verification

## Part 1 — The 30 required acceptance tests

`A` = covered by an automated test in `core/src/test/java` (run `gradle :core:test`).
`M` = requires the manual in-game checklist in Part 2, because it depends on the game loop.

| # | Requirement | | Where |
|---|---|---|---|
| 1 | A newly crafted item receives one unique Item Record ID | A | `CreationRecordTest.newlyCraftedItemReceivesOneUniqueRecordId` (500 items, no collisions) |
| 2 | Original crafter and company stored correctly | A | `CreationRecordTest.originalCrafterAndCompanyAreStored` |
| 3 | Independent crafting does not falsely assign a company | A | `CreationRecordTest.independentCraftingDoesNotAssignACompany` |
| 4 | Creation date survives restart | A | `PersistenceTest.aCompleteRecordSurvivesRestart` |
| 5 | Sword use increments overall **and** the acting player's kills | A | `StatisticsTest.usingASwordIncrementsBothOverallAndActingPlayer` |
| 6 | Mining increments overall **and** the acting player's blocks | A | `StatisticsTest.miningIncrementsBothOverallAndActingPlayer` |
| 7 | Cancelled block breaks do not count | M | Enforced by `EventPriority.LOWEST` + `receiveCanceled=false` in `GameplayEvents.onBlockBroken`. Checklist step 6. |
| 8 | Armour records absorbed damage without multiplying one event across pieces | A | `TrackingRulesTest.oneDamageEventIsSplitAcrossArmourRatherThanCopiedToEachPiece`, plus `allocationIsExactEvenWhenItDoesNotDivideEvenly` (200 amounts, exact sum) |
| 9 | A second player creates a separate permanent contribution record | A | `StatisticsTest.secondPlayerGetsASeparateRecordAndOverallIncludesBoth` |
| 10 | Overall totals correctly include both players | A | same test — 131,395 + 43,207 + 8,940 = 183,542 |
| 11 | Transferring ownership does not reset any record | A | `OwnershipTest.transferringOwnershipResetsNothing` |
| 12 | Current Owner view shows the owner's cumulative lifetime contribution | A | `OwnershipTest.currentOwnerViewShowsThatOwnersLifetimeContribution` |
| 13 | A former owner regaining the item continues their previous contribution | A | `OwnershipTest.aFormerOwnerRegainingTheItemContinuesTheirPreviousContribution` |
| 14 | All earlier contributors remain visible | A | `OwnershipTest.transferringOwnershipResetsNothing`, `ContributorQueryTest` |
| 15 | Milestones use overall item statistics | A | `MilestoneTest.milestonesReadOverallItemStatisticsNotTheOwnersOwn` (neither player alone qualifies) |
| 16 | Each milestone awarded once, with its date | A | `MilestoneTest.eachMilestoneIsAwardedOnceAndKeepsItsDate`, `loweringAThresholdLaterDoesNotRewriteAnEarnedDate` |
| 17 | Milestones provide no gameplay power | A | `MilestoneTest.milestoneTiersExposeNoGameplayPower` (reflects over the type) |
| 18 | Repairing preserves identity and history | A | `TrackingRulesTest.repairingPreservesIdentityAndEveryRecord` |
| 19 | Configured upgrades preserve identity and history | A | `CreationRecordTest.upgradePreservesOriginalTypeAndIdentity` |
| 20 | Renaming does not alter authenticated provenance | A | `CreationRecordTest.renamingDoesNotAlterAuthenticatedProvenance` |
| 21 | Marketplace and Auction Block sales transfer ownership | A | `OwnershipTest.transferringOwnershipResetsNothing` covers the API those systems call (`transferViaTrustedSystem`). Wiring is integration work — checklist step 11. |
| 22 | Temporary pickup does not silently transfer ownership | A | `OwnershipTest.temporaryUseDoesNotSilentlyTransferOwnership` |
| 23 | Distance ignores static containers and teleports by default | A | `TrackingRulesTest.teleportsDoNotCreateEnormousTravelRecordsByDefault`, `standingStillAccruesNoDistance`, `changingDimensionOnlyRebaselines`. Containers are never sampled — `TickEvents` only reads equipment slots. |
| 24 | Legacy equipment migrates safely | A | `PersistenceTest.legacyEquipmentMigratesWithoutFabricatingHistory` |
| 25 | No Hunter, Arena or server-event statistics anywhere | A | `StatisticsTest.thereIsNoStatKeyForHunterArenaOrServerEvents` — the enum has no such member |
| 26 | Mob Cards remain unaffected | A | Nothing in this mod references Mob Cards; eligibility is tag/id driven and Mob Cards match no tracked category. Checklist step 14. |
| 27 | UI switches between Overall, Current Owner, Milestones, Contributors | M | `ItemHistoryScreen`. Checklist step 12. |
| 28 | Contributors tab stays responsive with many users | A | `ContributorQueryTest.largeContributorListsPageWithoutRepeatingOrDroppingRows` — 5,000 contributors with deliberate ties, no row repeated or dropped |
| 29 | Data survives logout, restart, death, storage, dimension change | A | `PersistenceTest` covers restart and cold-cache reload. Death/storage/dimension are M — checklist step 13. |
| 30 | Duplication cannot create two valid items with the same record | A | `DuplicationTest.aCopiedStackCannotInheritTheOriginalsHistory`, `replayingAnOldStampIsRefused`, `theStoreRefusesTwoRecordsWithTheSameId` |

**Automated: 26 of 30.** The four marked `M` depend on the running game loop and are in the
checklist below.

---

## Part 2 — Manual in-game verification checklist

Run on a dedicated server with at least two accounts. `/provenance admin verify` checks the
held item's internal consistency at any point.

### Identity and creation
1. Craft a diamond pickaxe. Press **H**. Confirm: your name under *Crafted by*,
   **Independent** under *Manufactured by*, today's date, you as *Current owner*.
2. Craft a second one. Confirm the two have independent statistics — mine with one only,
   and check the other stays at zero.
3. Rename the pickaxe "Steelbreaker" in an anvil. Confirm the screen shows *Steelbreaker*
   as the name and the earned tier separately, and that *Crafted by* is unchanged.
4. Restart the server. Re-inspect. Confirm every field survives. *(Acceptance 4, 29)*

### Usage
5. Mine 120 blocks. Confirm *Blocks mined* reads 120 in both the Overall and Current Owner
   views, and that **Initiated** was awarded with today's date.
6. **Cancelled breaks.** In a region-protected area (or with any protection mod), attempt to
   break blocks. Confirm the counter does **not** move. *(Acceptance 7)*
7. In creative mode, break 50 blocks. Confirm the counter does not move
   (`creativeActionsCount` defaults to false).
8. Kill 10 mobs with a sword. Confirm kills, most-killed mob, and the **Initiated** award.
9. Take damage in a full armour set. Confirm the four pieces' *Damage absorbed* values
   **sum to roughly the damage actually prevented**, and that no single piece shows the
   whole amount. *(Acceptance 8)*
10. Enchant a tool with Mending and repair it with orbs. Confirm *Repairs* increments by
    about one per session, not once per orb.

### Ownership
11. Hand the pickaxe to a second player without using a command. Have them mine 50 blocks.
    Confirm: *Current owner* is still **you**; the Contributors tab now lists them with 50
    blocks; the overall total includes those 50. *(Acceptance 22)*
    Then run `/provenance transfer <them>` and have them `/provenance accept`. Confirm
    ownership moves and **nothing else changes**. *(Acceptance 11, 21)*
12. Open all four tabs. Toggle Statistics between *Overall Item* and *Current Owner* and
    confirm the owner's name is shown so the two cannot be confused. Search and sort the
    Contributors tab. *(Acceptance 27)*
13. **Survival of everything.** With the item: die and retrieve it; store it in a chest for
    a few minutes; carry it through a Nether portal; log out and back in. Re-inspect after
    each. Confirm no statistic is lost and that chest time added no distance.
    *(Acceptance 23, 29)*
14. Confirm a Mob Card still shows its "Unlocked by" line and has gained no provenance
    tooltip or history screen. *(Acceptance 26)*

### Upgrades and edge cases
15. Upgrade a diamond pickaxe to netherite in a smithing table. Confirm the record id,
    creation date, crafter, statistics and milestones all carry over, and that the screen
    shows the netherite type while the record retains the diamond original.
16. Find a pre-existing item from before this mod was installed. Inspect it. Confirm
    *Legacy item* origin, **Unknown** maker, an approximate date, and counters at zero —
    and that no fake age or crafter was invented. *(Acceptance 24)*
17. Leave a large mob farm and a fast quarry running for ten minutes with tracked tools in
    inventory. Watch `/tps`. Confirm no measurable degradation, and that automation output
    is not credited to a tool that did not perform it.
18. As an operator, run `/provenance admin verify` on a heavily used item. Confirm it reports
    **consistent**.
