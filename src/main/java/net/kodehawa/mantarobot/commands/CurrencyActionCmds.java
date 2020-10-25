/*
 * Copyright (C) 2016-2020 David Rubio Escares / Kodehawa
 *
 *  Mantaro is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  Mantaro is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro.  If not, see http://www.gnu.org/licenses/
 */

package net.kodehawa.mantarobot.commands;

import com.google.common.eventbus.Subscribe;
import net.kodehawa.mantarobot.commands.currency.item.*;
import net.kodehawa.mantarobot.commands.currency.item.special.Axe;
import net.kodehawa.mantarobot.commands.currency.item.special.FishRod;
import net.kodehawa.mantarobot.commands.currency.item.special.Pickaxe;
import net.kodehawa.mantarobot.commands.currency.pets.HousePet;
import net.kodehawa.mantarobot.commands.currency.pets.HousePetType;
import net.kodehawa.mantarobot.commands.currency.profile.Badge;
import net.kodehawa.mantarobot.commands.currency.seasons.SeasonPlayer;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.modules.commands.SimpleCommand;
import net.kodehawa.mantarobot.core.modules.commands.base.CommandCategory;
import net.kodehawa.mantarobot.core.modules.commands.base.Context;
import net.kodehawa.mantarobot.core.modules.commands.help.HelpContent;
import net.kodehawa.mantarobot.core.modules.commands.i18n.I18nContext;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.entities.DBUser;
import net.kodehawa.mantarobot.db.entities.Player;
import net.kodehawa.mantarobot.utils.RandomCollection;
import net.kodehawa.mantarobot.utils.RatelimitUtils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.commands.campaign.Campaign;
import net.kodehawa.mantarobot.utils.commands.ratelimit.IncreasingRateLimiter;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.kodehawa.mantarobot.commands.currency.item.ItemHelper.handleDurability;

@Module
public class CurrencyActionCmds {
    private final SecureRandom random = new SecureRandom();

    @Subscribe
    public void mine(CommandRegistry cr) {
        cr.register("mine", new SimpleCommand(CommandCategory.CURRENCY) {
            final IncreasingRateLimiter rateLimiter = new IncreasingRateLimiter.Builder()
                    .limit(1)
                    .spamTolerance(3)
                    .cooldown(5, TimeUnit.MINUTES)
                    .maxCooldown(5, TimeUnit.MINUTES)
                    .incrementDivider(10)
                    .premiumAware(true)
                    .pool(MantaroData.getDefaultJedisPool())
                    .prefix("mine")
                    .build();

            @Override
            protected void call(Context ctx, String content, String[] args) {
                final var isSeasonal = ctx.isSeasonal();
                final var languageContext = ctx.getLanguageContext();

                final var db = MantaroData.db();

                final var player = ctx.getPlayer();
                final var playerData = player.getData();

                final var seasonalPlayer = ctx.getSeasonPlayer();
                final var seasonalPlayerData = seasonalPlayer.getData();

                final var dbUser = ctx.getDBUser();
                final var userData = dbUser.getData();
                final var marriage = userData.getMarriage();

                final var inventory = isSeasonal ?
                        seasonalPlayer.getInventory() : player.getInventory();

                var equipped = isSeasonal ?
                        seasonalPlayerData.getEquippedItems().of(PlayerEquipment.EquipmentType.PICK) :
                        userData.getEquippedItems().of(PlayerEquipment.EquipmentType.PICK);

                if (equipped == 0) {
                    ctx.sendLocalized("commands.mine.not_equipped", EmoteReference.ERROR);
                    return;
                }

                var item = (Pickaxe) ItemHelper.fromId(equipped);

                if (!RatelimitUtils.ratelimit(rateLimiter, ctx, false)) {
                    return;
                }

                var money = Math.max(30, random.nextInt(200)); //30 to 150 credits.
                money += item.getMoneyIncrease();

                var waifuHelp = false;
                if (ItemHelper.handleEffect(
                        PlayerEquipment.EquipmentType.POTION, userData.getEquippedItems(), ItemReference.WAIFU_PILL, dbUser)) {

                    if (userData.getWaifus().entrySet().stream().anyMatch((w) -> w.getValue() > 10_000_000L)) {
                        money += Math.max(45, random.nextInt(200));
                        waifuHelp = true;
                    }
                }

                var reminder = random.nextInt(6) == 0 && item == ItemReference.BROM_PICKAXE ?
                        languageContext.get("commands.mine.reminder") : "";

                var message = String.format(languageContext.get("commands.mine.success") + reminder,
                        item.getEmoji(), money, item.getName()
                );

                var hasPotion = ItemHelper.handleEffect(
                        PlayerEquipment.EquipmentType.POTION, userData.getEquippedItems(), ItemReference.POTION_HASTE, dbUser
                );

                var petHelp = false;

                if (marriage != null && marriage.getData().getPet() != null) {
                    var pet = marriage.getData().getPet();
                    if (pet != null) {
                        var rewards = handlePetBuff(pet, HousePetType.HousePetAbility.CATCH, languageContext, false);
                        money += rewards.getMoney();
                        message += rewards.getResult();

                        if (rewards.getMoney() > 0)
                            petHelp = true;
                    }
                }

                //Diamond find
                if (random.nextInt(400) > (hasPotion || petHelp ? 290 : 350)) {

                    if (inventory.getAmount(ItemReference.DIAMOND) == 5000) {
                        message += "\n" + languageContext.withRoot("commands", "mine.diamond.overflow");
                        money += ItemReference.DIAMOND.getValue() * 0.9;
                    } else {
                        var amount = 1;

                        if (item == ItemReference.STAR_PICKAXE ||
                                item == ItemReference.COMET_PICKAXE || item == ItemReference.MOON_PICK) {
                            amount += random.nextInt(2);
                        }

                        if (item == ItemReference.SPARKLE_PICKAXE) {
                            amount += random.nextInt(4);
                        }

                        inventory.process(new ItemStack(ItemReference.DIAMOND, amount));
                        message += "\n" + EmoteReference.DIAMOND + String.format(
                                languageContext.withRoot("commands", "mine.diamond.success"), amount
                        );
                    }

                    playerData.addBadgeIfAbsent(Badge.MINER);
                }

                //Gem find
                if (random.nextInt(400) > (hasPotion ? 278 : (petHelp ? 250 : 325))) {

                    List<Item> gem = Stream.of(ItemReference.ALL)
                            .filter(i -> i.getItemType() == ItemType.MINE && !i.isHidden() && i.isSellable())
                            .collect(Collectors.toList());

                    //top notch handling for gems, 10/10 implementation -ign
                    var selectedGem = new ItemStack(gem.get(random.nextInt(gem.size())), Math.max(1, random.nextInt(5)));
                    var itemGem = selectedGem.getItem();

                    if (inventory.getAmount(itemGem) + selectedGem.getAmount() >= 5000) {
                        message += "\n" + languageContext.withRoot("commands", "mine.gem.overflow");
                        money += itemGem.getValue() * 0.9;
                    } else {
                        inventory.process(selectedGem);
                        message += "\n" + EmoteReference.MEGA + String.format(
                                languageContext.withRoot("commands", "mine.gem.success"),
                                itemGem.getEmoji() + " x" + selectedGem.getAmount()
                        );
                    }

                    if (waifuHelp) {
                        message += "\n" + languageContext.get("commands.mine.waifu_help");
                    }

                    playerData.addBadgeIfAbsent(Badge.GEM_FINDER);
                }

                if (dbUser.isPremium() && money > 0) {
                    money += random.nextInt(money);
                }

                //Sparkle find
                if ((random.nextInt(400) > 395 && item == ItemReference.COMET_PICKAXE) ||
                        (random.nextInt(400) > 390 && (item == ItemReference.STAR_PICKAXE ||
                        item == ItemReference.SPARKLE_PICKAXE || item == ItemReference.HELLFIRE_PICK))) {

                    var gem = ItemReference.SPARKLE_FRAGMENT;

                    if (inventory.getAmount(gem) + 1 >= 5000) {
                        message += "\n" + languageContext.withRoot("commands", "mine.sparkle.overflow");
                        money += gem.getValue() * 0.9;
                    } else {
                        inventory.process(new ItemStack(gem, 1));
                        message += "\n" + EmoteReference.MEGA +
                                String.format(languageContext.withRoot("commands", "mine.sparkle.success"), gem.getEmoji());
                    }

                    playerData.addBadgeIfAbsent(Badge.GEM_FINDER);
                }

                var key = db.getPremiumKey(dbUser.getData().getPremiumKey());
                if (random.nextInt(400) > 392) {

                    var crate = (key != null && key.getDurationDays() > 1) ?
                            ItemReference.MINE_PREMIUM_CRATE : ItemReference.MINE_CRATE;

                    if (inventory.getAmount(crate) + 1 > 5000) {
                        message += "\n" + languageContext.withRoot("commands", "mine.crate.overflow");
                    } else {
                        inventory.process(new ItemStack(crate, 1));
                        message += "\n" + EmoteReference.MEGA +
                                String.format(languageContext.withRoot("commands", "mine.crate.success"), crate.getEmoji(), crate.getName());
                    }
                }

                if (playerData.shouldSeeCampaign()) {
                    message += Campaign.PREMIUM.getStringFromCampaign(languageContext, dbUser.isPremium());
                    playerData.markCampaignAsSeen();
                }

                if (isSeasonal) {
                    seasonalPlayer.addMoney(money);
                    seasonalPlayer.saveAsync();
                } else {
                    playerData.incrementMiningExperience(random);
                    player.addMoney(money);
                }

                //Due to badges.
                player.save();

                if (marriage != null) {
                    marriage.save();
                }

                handleItemDurability(item, ctx, player, dbUser, seasonalPlayer, "commands.mine.autoequip.success", isSeasonal);

                ctx.send(message);
            }

            @Override
            public HelpContent help() {
                return new HelpContent.Builder()
                        .setDescription("Mines minerals to gain some credits. A bit more lucrative than loot, but needs pickaxes.")
                        .setUsage("`~>mine` - Mines. You can gain minerals or mineral fragments by mining. " +
                                "This can used later on to cast rods or picks for better chances.")
                        .setSeasonal(true)
                        .build();
            }
        });
    }

    @Subscribe
    public void fish(CommandRegistry cr) {
        IncreasingRateLimiter fishRatelimiter = new IncreasingRateLimiter.Builder()
                .limit(1)
                .spamTolerance(2)
                .cooldown(4, TimeUnit.MINUTES)
                .maxCooldown(4, TimeUnit.MINUTES)
                .incrementDivider(10)
                .pool(MantaroData.getDefaultJedisPool())
                .prefix("fish")
                .premiumAware(true)
                .build();

        cr.register("fish", new SimpleCommand(CommandCategory.CURRENCY) {
            @Override
            protected void call(Context ctx, String content, String[] args) {
                final var isSeasonal = ctx.isSeasonal();
                final var languageContext = ctx.getLanguageContext();

                final var player = ctx.getPlayer();
                final var playerData = ctx.getPlayer().getData();

                final var seasonPlayer = ctx.getSeasonPlayer();
                final var dbUser = ctx.getDBUser();
                final var marriage = dbUser.getData().getMarriage();
                final var playerInventory = isSeasonal ? seasonPlayer.getInventory() : player.getInventory();

                FishRod item;

                var equipped = isSeasonal ?
                        //seasonal equipped
                        seasonPlayer.getData().getEquippedItems().of(PlayerEquipment.EquipmentType.ROD) :
                        //not seasonal
                        dbUser.getData().getEquippedItems().of(PlayerEquipment.EquipmentType.ROD);

                if (equipped == 0) {
                    ctx.sendLocalized("commands.fish.no_rod_equipped", EmoteReference.ERROR);
                    return;
                }

                //It can only be a rod, lol.
                item = (FishRod) ItemHelper.fromId(equipped);

                if (!RatelimitUtils.ratelimit(fishRatelimiter, ctx, false)) {
                    return;
                }

                //Level but starting at 0.
                var nominalLevel = item.getLevel() - 3;
                var extraMessage = "";
                var chance = random.nextInt(100);

                if (chance < 10) {
                    //Here your fish rod got dusty. Yes, on the sea.
                    var level = dbUser.getData().increaseDustLevel(random.nextInt(4));
                    ctx.sendLocalized("commands.fish.dust", EmoteReference.TALKING, level);
                    dbUser.save();

                    handleItemDurability(item, ctx, player, dbUser, seasonPlayer, "commands.fish.autoequip.success", isSeasonal);
                    return;
                } else if (chance < 35) {
                    //Here you found trash.
                    List<Item> common = Stream.of(ItemReference.ALL)
                            .filter(i -> i.getItemType() == ItemType.COMMON && !i.isHidden() && i.isSellable() && i.getValue() < 45)
                            .collect(Collectors.toList());

                    var selected = common.get(random.nextInt(common.size()));
                    if (playerInventory.getAmount(selected) >= 5000) {
                        ctx.sendLocalized("commands.fish.trash.overflow", EmoteReference.SAD);

                        handleItemDurability(item, ctx, player, dbUser, seasonPlayer, "commands.fish.autoequip.success", isSeasonal);
                        return;
                    }

                    playerInventory.process(new ItemStack(selected, 1));
                    ctx.sendLocalized("commands.fish.trash.success", EmoteReference.EYES, selected.getEmoji());
                } else {
                    //Here you actually caught fish, congrats.
                    List<Item> fish = Stream.of(ItemReference.ALL)
                            .filter(i -> i.getItemType() == ItemType.FISHING && !i.isHidden() && i.isSellable())
                            .collect(Collectors.toList());
                    RandomCollection<Item> fishItems = new RandomCollection<>();

                    var money = 0;
                    var buff = ItemHelper.handleEffect(
                            PlayerEquipment.EquipmentType.BUFF,
                            dbUser.getData().getEquippedItems(),
                            ItemReference.FISHING_BAIT, dbUser
                    );

                    var amount = Math.max(1, random.nextInt(item.getLevel()));

                    if (buff) {
                        amount = Math.max(1, random.nextInt(item.getLevel() + 4));
                        extraMessage += "\n" + languageContext.get("commands.fish.bait");
                    }

                    if (nominalLevel >= 2) {
                        amount += random.nextInt(4);
                    }

                    fish.forEach((i1) -> fishItems.add(3, i1));

                    if (marriage != null && marriage.getData().getPet() != null) {
                        var pet = marriage.getData().getPet();

                        if (pet != null) {
                            HousePet.ActivityReward rewards = handlePetBuff(pet, HousePetType.HousePetAbility.FISH, languageContext);
                            amount += rewards.getItems();
                            money += rewards.getMoney();
                            extraMessage += rewards.getResult();
                        }
                    }

                    //Basically more chance if you have a better rod.
                    if (chance > (70 - nominalLevel)) {
                        money = Math.max(25, random.nextInt(130 + (3 * nominalLevel)));
                    }

                    //START OF WAIFU HELP IMPLEMENTATION
                    boolean waifuHelp = false;
                    if (ItemHelper.handleEffect(
                            PlayerEquipment.EquipmentType.POTION, dbUser.getData().getEquippedItems(), ItemReference.WAIFU_PILL, dbUser)) {

                        if (dbUser.getData().getWaifus().entrySet().stream().anyMatch((w) -> w.getValue() > 10_000_000L)) {
                            money += Math.max(10, random.nextInt(100));
                            waifuHelp = true;
                        }
                    }
                    //END OF WAIFU HELP IMPLEMENTATION

                    //START OF FISH LOOT CRATE HANDLING
                    if (random.nextInt(400) > 380) {
                        var crate = dbUser.isPremium() ? ItemReference.FISH_PREMIUM_CRATE : ItemReference.FISH_CRATE;

                        if (playerInventory.getAmount(crate) >= 5000) {
                            extraMessage += "\n" + languageContext.get("commands.fish.crate.overflow");
                        } else {
                            playerInventory.process(new ItemStack(crate, 1));
                            extraMessage += "\n" + EmoteReference.MEGA +
                                    String.format(languageContext.get("commands.fish.crate.success"), crate.getEmoji(), crate.getName());
                        }
                    }
                    //END OF FISH LOOT CRATE HANDLING

                    if ((item == ItemReference.SPARKLE_ROD || item == ItemReference.HELLFIRE_ROD) && random.nextInt(30) > 20) {

                        if (random.nextInt(100) > 96) {
                            fish.addAll(Stream.of(ItemReference.ALL)
                                    .filter(i -> i.getItemType() == ItemType.FISHING_RARE && !i.isHidden() && i.isSellable())
                                    .collect(Collectors.toList())
                            );
                        }

                        playerInventory.process(new ItemStack(ItemReference.SHARK, 1));
                        extraMessage += "\n" + EmoteReference.MEGA + String.format(
                                languageContext.get("commands.fish.shark_success"), ItemReference.SHARK.getEmoji()
                        );

                        player.getData().setSharksCaught(player.getData().getSharksCaught() + 1);
                    }

                    List<ItemStack> list = new ArrayList<>(amount);
                    var overflow = false;

                    for (int i = 0; i < amount; i++) {
                        Item it = fishItems.next();
                        if (playerInventory.getAmount(it) >= 5000) {
                            overflow = true;
                            continue;
                        }

                        list.add(new ItemStack(it, 1));
                    }
                    // END OF ITEM ADD HANDLING

                    if (overflow) {
                        extraMessage += "\n" + String.format(languageContext.get("commands.fish.overflow"), EmoteReference.SAD);
                    }

                    List<ItemStack> reducedList = ItemStack.reduce(list);
                    playerInventory.process(reducedList);

                    if (isSeasonal) {
                        seasonPlayer.addMoney(money);
                    } else {
                        player.addMoney(money);
                        player.getData().incrementFishingExperience(random);
                    }

                    var itemDisplay = ItemStack.toString(reducedList);
                    var foundFish = !reducedList.isEmpty();

                    //Add fisher badge if the player found fish successfully.
                    if (foundFish) {
                        player.getData().addBadgeIfAbsent(Badge.FISHER);
                    }

                    if (nominalLevel >= 3 && random.nextInt(110) > 90) {
                        playerInventory.process(new ItemStack(ItemReference.SHELL, 1));
                        extraMessage += "\n" + EmoteReference.MEGA + String.format(
                                languageContext.get("commands.fish.fossil_success"), ItemReference.SHELL.getEmoji()
                        );
                    }


                    if (dbUser.isPremium() && money > 0) {
                        money += random.nextInt(money);
                    }

                    if (playerData.shouldSeeCampaign()) {
                        extraMessage += Campaign.PREMIUM.getStringFromCampaign(languageContext, dbUser.isPremium());
                        playerData.markCampaignAsSeen();
                    }

                    //START OF REPLY HANDLING
                    //Didn't find a thingy thing.
                    if (money == 0 && !foundFish) {
                        int level = dbUser.getData().increaseDustLevel(random.nextInt(4));
                        ctx.sendLocalized("commands.fish.dust", EmoteReference.TALKING, level);
                        dbUser.save();

                        handleItemDurability(item, ctx, player, dbUser, seasonPlayer, "commands.fish.autoequip.success", isSeasonal);
                        return;
                    }

                    //if there's money, but not fish
                    if (money > 0 && !foundFish) {
                        ctx.sendFormat(languageContext.get("commands.fish.success_money_noitem") + extraMessage, item.getEmoji(), money);
                    } else if (foundFish && money == 0) { //there's fish, but no money
                        ctx.sendFormat(languageContext.get("commands.fish.success") + extraMessage, item.getEmoji(), itemDisplay);
                    } else if (money > 0) { //there's money and fish
                        ctx.sendFormat(languageContext.get("commands.fish.success_money") + extraMessage,
                                item.getEmoji(), itemDisplay, money, (waifuHelp ? "\n" + languageContext.get("commands.fish.waifu_help") : "")
                        );
                    }
                    //END OF REPLY HANDLING
                }

                //Save all changes to the player object.
                player.save();

                if (isSeasonal) {
                    seasonPlayer.save();
                }

                // Save pet stats.
                if (marriage != null) {
                    marriage.save();
                }

                handleItemDurability(item, ctx, player, dbUser, seasonPlayer, "commands.fish.autoequip.success", isSeasonal);
            }

            @Override
            public HelpContent help() {
                return new HelpContent.Builder()
                        .setDescription("Starts a fishing session.")
                        .setUsage("`~>fish` - Starts fishing." +
                                " You can gain credits and fish items by fishing, which can be used later on for casting.")
                        .setSeasonal(true)
                        .build();
            }
        });
    }

    @Subscribe
    public void chop(CommandRegistry cr) {
        final IncreasingRateLimiter rateLimiter = new IncreasingRateLimiter.Builder()
                .limit(1)
                .spamTolerance(3)
                .cooldown(4, TimeUnit.MINUTES)
                .maxCooldown(4, TimeUnit.MINUTES)
                .incrementDivider(10)
                .premiumAware(true)
                .pool(MantaroData.getDefaultJedisPool())
                .prefix("chop")
                .build();

        // TODO: Loot crates, Rare items
        cr.register("chop", new SimpleCommand(CommandCategory.CURRENCY) {
            @Override
            protected void call(Context ctx, String content, String[] args) {
                final var isSeasonal = ctx.isSeasonal();
                final var languageContext = ctx.getLanguageContext();

                final var player = ctx.getPlayer();
                final var playerData = ctx.getPlayer().getData();

                final var seasonPlayer = ctx.getSeasonPlayer();
                final var dbUser = ctx.getDBUser();
                final var userData = dbUser.getData();
                final var marriage = userData.getMarriage();
                final var playerInventory = isSeasonal ? seasonPlayer.getInventory() : player.getInventory();

                var extraMessage = "\n";
                var equipped = isSeasonal ?
                        //seasonal equipped
                        seasonPlayer.getData().getEquippedItems().of(PlayerEquipment.EquipmentType.AXE) :
                        //not seasonal
                        userData.getEquippedItems().of(PlayerEquipment.EquipmentType.AXE);

                if (equipped == 0) {
                    ctx.sendLocalized("commands.chop.not_equipped", EmoteReference.ERROR);
                    return;
                }

                final var item = (Axe) ItemHelper.fromId(equipped);

                if (!RatelimitUtils.ratelimit(rateLimiter, ctx, false)) {
                    return;
                }

                var chance = random.nextInt(100);
                var hasPotion = ItemHelper.handleEffect(
                        PlayerEquipment.EquipmentType.POTION, userData.getEquippedItems(), ItemReference.POTION_HASTE, dbUser);
                if (hasPotion)
                    chance += 10;

                if (chance < 10) {
                    // Found nothing.
                    int level = userData.increaseDustLevel(random.nextInt(5));
                    dbUser.save();
                    // Process axe durability.
                    handleItemDurability(item, ctx, player, dbUser, seasonPlayer, "commands.chop.autoequip.success", isSeasonal);

                    ctx.sendLocalized("commands.chop.dust", EmoteReference.SAD, level);
                } else {
                    var money = chance > 50 ? random.nextInt(100) : 0;
                    var amount = random.nextInt(8);
                    money += item.getMoneyIncrease();

                    if (marriage != null && marriage.getData().getPet() != null) {
                        var pet = marriage.getData().getPet();

                        if (pet != null) {
                            HousePet.ActivityReward rewards = handlePetBuff(pet, HousePetType.HousePetAbility.CHOP, languageContext);
                            amount += rewards.getItems();
                            money += rewards.getMoney();
                            extraMessage += rewards.getResult();
                        }
                    }

                    if (hasPotion)
                        amount += 3;

                    // ---- Start of drop handling.
                    RandomCollection<Item> items = new RandomCollection<>();
                    var toDrop = handleChopDrop();
                    toDrop.forEach(i -> items.add(3, i));

                    List<Item> list = new ArrayList<>(amount);
                    for (int i = 0; i < amount; i++) {
                        list.add(items.next());
                    }

                    ArrayList<ItemStack> ita = new ArrayList<>();
                    list.forEach(it -> ita.add(new ItemStack(it, 1)));
                    var found = !ita.isEmpty();

                    // Make so it drops some decent amount of wood lol
                    if (ita.stream().anyMatch(is -> is.getItem() == ItemReference.WOOD)) {
                        ita.add(new ItemStack(ItemReference.WOOD, Math.max(1, random.nextInt(7))));
                    } else if (found) {
                        // Guarantee at least one wood.
                        ita.add(new ItemStack(ItemReference.WOOD, 1));
                    }

                    // Reduce item stacks (aka join them) and process it.
                    var reducedStack = ItemStack.reduce(ita);
                    var itemDisplay = ItemStack.toString(reducedStack);

                    playerInventory.process(reducedStack);

                    // Add money
                    if (isSeasonal) {
                        seasonPlayer.addMoney(money);
                    } else {
                        player.addMoney(money);
                        player.getData().incrementChopExperience(random);
                    }

                    // Ah yes, sellout
                    if (dbUser.isPremium() && money > 0) {
                        money += random.nextInt(money);
                    }

                    if (found) {
                        playerData.addBadgeIfAbsent(Badge.CHOPPER);
                    }

                    if (playerData.shouldSeeCampaign()) {
                        extraMessage += Campaign.PREMIUM.getStringFromCampaign(languageContext, dbUser.isPremium());
                        playerData.markCampaignAsSeen();
                    }

                    // Show a message depending on the outcome.
                    if (money > 0 && !found) {
                        ctx.sendFormat(languageContext.get("commands.chop.success_money_noitem") + extraMessage, item.getEmoji(), money);
                    } else if (found && money == 0) {
                        ctx.sendFormat(languageContext.get("commands.chop.success_only_item") + extraMessage, item.getEmoji(), itemDisplay);
                    } else if (!found && money == 0) {
                        // This doesn't actually increase the dust level, though.
                        var level = userData.getDustLevel();
                        ctx.sendLocalized("commands.chop.dust", EmoteReference.SAD, level);
                    } else {
                        ctx.sendFormat(languageContext.get("commands.chop.success") + extraMessage, item.getEmoji(), itemDisplay, money);
                    }

                    player.save();

                    // Save pet stuff.
                    if (marriage != null) {
                        marriage.save();
                    }

                    // Process axe durability.
                    handleItemDurability(item, ctx, player, dbUser, seasonPlayer, "commands.chop.autoequip.success", isSeasonal);
                }
            }

            @Override
            public HelpContent help() {
                return new HelpContent.Builder()
                        .setDescription("Starts a chopping session.")
                        .setUsage("`~>chop` - Starts chopping trees." +
                                " You can gain credits and items by chopping, which can be used later on for casting, specially tools.")
                        .setSeasonal(true)
                        .build();
            }
        });
    }

    private HousePet.ActivityReward handlePetBuff(HousePet pet, HousePetType.HousePetAbility required,
                                                  I18nContext languageContext) {
        return handlePetBuff(pet, required, languageContext, true);
    }


    private HousePet.ActivityReward handlePetBuff(HousePet pet, HousePetType.HousePetAbility required,
                                                  I18nContext languageContext, boolean needsItem) {

        HousePet.ActivityResult ability = pet.handleAbility(required);
        if (ability.passed()) {
            var itemIncrease = 0;
            if (needsItem) {
                itemIncrease = random.nextInt(pet.getType().getMaxItemBuildup(pet.getLevel()));
            }

            var moneyIncrease = random.nextInt(pet.getType().getMaxCoinBuildup(pet.getLevel()));
            var message = "\n" + pet.buildMessage(ability, languageContext, moneyIncrease, itemIncrease);

            return new HousePet.ActivityReward(itemIncrease, moneyIncrease, message);
        } else if (!ability.passed() && !ability.getLanguageString().isEmpty()) {
            var message = "\n" + pet.buildMessage(ability, languageContext, 0, 0);
            return new HousePet.ActivityReward(0, 0, message);
        }

        return new HousePet.ActivityReward(0, 0, "");
    }

    private List<Item> handleChopDrop() {
        var all = Arrays.stream(ItemReference.ALL)
                .filter(i -> i.getItemType() == ItemType.CHOP_DROP)
                .collect(Collectors.toList());

        return all.stream()
                .sorted(Comparator.comparingLong(Item::getValue))
                .collect(Collectors.toList());
    }

    private void handleItemDurability(Item item, Context ctx, Player player, DBUser dbUser,
                                      SeasonPlayer seasonPlayer, String i18n, boolean isSeasonal) {

        var breakage = handleDurability(ctx, item, player, dbUser, seasonPlayer, isSeasonal);
        if (!breakage.getKey())
            return;

        //We need to get this again since reusing the old ones will cause :fire:
        var finalPlayer = breakage.getValue();
        var inventory = finalPlayer.getInventory();

        if (dbUser.getData().isAutoEquip() && inventory.containsItem(item)) {
            dbUser.getData().getEquippedItems().equipItem(item);
            inventory.process(new ItemStack(item, -1));

            finalPlayer.save();
            dbUser.save();

            ctx.sendLocalized(i18n, EmoteReference.CORRECT, item.getName());
        }
    }
}
