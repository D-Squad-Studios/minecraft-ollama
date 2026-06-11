package net.minecraft.world.inventory;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.ClientSideMerchant;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

public class MerchantMenu extends AbstractContainerMenu {
    protected static final int PAYMENT1_SLOT = 0;
    protected static final int PAYMENT2_SLOT = 1;
    protected static final int RESULT_SLOT = 2;
    private static final int INV_SLOT_START = 3;
    private static final int INV_SLOT_END = 30;
    private static final int USE_ROW_SLOT_START = 30;
    private static final int USE_ROW_SLOT_END = 39;
    private static final int SELLSLOT1_X = 136;
    private static final int SELLSLOT2_X = 162;
    private static final int BUYSLOT_X = 220;
    private static final int ROW_Y = 37;
    private final Merchant trader;
    private final MerchantContainer tradeContainer;
    private int merchantLevel;
    private boolean showProgressBar;
    private boolean canRestock;

    public MerchantMenu(int pContainerId, Inventory pPlayerInventory) {
        this(pContainerId, pPlayerInventory, new ClientSideMerchant(pPlayerInventory.player));
    }

    public MerchantMenu(int pContainerId, Inventory pPlayerInventory, Merchant pTrader) {
        super(MenuType.MERCHANT, pContainerId);
        this.trader = pTrader;
        this.tradeContainer = new MerchantContainer(pTrader);
        this.addSlot(new Slot(this.tradeContainer, 0, 136, 37));
        this.addSlot(new Slot(this.tradeContainer, 1, 162, 37));
        this.addSlot(new MerchantResultSlot(pPlayerInventory.player, pTrader, this.tradeContainer, 2, 220, 37));
        this.addStandardInventorySlots(pPlayerInventory, 108, 84);
    }

    public void setShowProgressBar(boolean pShowProgressBar) {
        this.showProgressBar = pShowProgressBar;
    }

    @Override
    public void slotsChanged(Container pInventory) {
        this.tradeContainer.updateSellItem();
        super.slotsChanged(pInventory);
    }

    public void setSelectionHint(int pCurrentRecipeIndex) {
        this.tradeContainer.setSelectionHint(pCurrentRecipeIndex);
    }

    @Override
    public boolean stillValid(Player pPlayer) {
        return this.trader.stillValid(pPlayer);
    }

    public int getTraderXp() {
        return this.trader.getVillagerXp();
    }

    public int getFutureTraderXp() {
        return this.tradeContainer.getFutureXp();
    }

    public void setXp(int pXp) {
        this.trader.overrideXp(pXp);
    }

    public int getTraderLevel() {
        return this.merchantLevel;
    }

    public void setMerchantLevel(int pLevel) {
        this.merchantLevel = pLevel;
    }

    public void setCanRestock(boolean pCanRestock) {
        this.canRestock = pCanRestock;
    }

    public boolean canRestock() {
        return this.canRestock;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack pStack, Slot pSlot) {
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player pPlayer, int pIndex) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(pIndex);
        if (slot != null && slot.hasItem()) {
            ItemStack itemstack1 = slot.getItem();
            itemstack = itemstack1.copy();
            if (pIndex == 2) {
                if (!this.moveItemStackTo(itemstack1, 3, 39, true)) {
                    return ItemStack.EMPTY;
                }

                slot.onQuickCraft(itemstack1, itemstack);
                this.playTradeSound();
            } else if (pIndex != 0 && pIndex != 1) {
                if (pIndex >= 3 && pIndex < 30) {
                    if (!this.moveItemStackTo(itemstack1, 30, 39, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (pIndex >= 30 && pIndex < 39 && !this.moveItemStackTo(itemstack1, 3, 30, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(itemstack1, 3, 39, false)) {
                return ItemStack.EMPTY;
            }

            if (itemstack1.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            if (itemstack1.getCount() == itemstack.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(pPlayer, itemstack1);
        }

        return itemstack;
    }

    private void playTradeSound() {
        if (!this.trader.isClientSide()) {
            Entity entity = (Entity)this.trader;
            entity.level().playLocalSound(entity.getX(), entity.getY(), entity.getZ(), this.trader.getNotifyTradeSound(), SoundSource.NEUTRAL, 1.0F, 1.0F, false);
        }
    }

    @Override
    public void removed(Player pPlayer) {
        super.removed(pPlayer);
        this.trader.setTradingPlayer(null);
        if (!this.trader.isClientSide()) {
            if (!pPlayer.isAlive() || pPlayer instanceof ServerPlayer && ((ServerPlayer)pPlayer).hasDisconnected()) {
                ItemStack itemstack = this.tradeContainer.removeItemNoUpdate(0);
                if (!itemstack.isEmpty()) {
                    pPlayer.drop(itemstack, false);
                }

                itemstack = this.tradeContainer.removeItemNoUpdate(1);
                if (!itemstack.isEmpty()) {
                    pPlayer.drop(itemstack, false);
                }
            } else if (pPlayer instanceof ServerPlayer) {
                pPlayer.getInventory().placeItemBackInInventory(this.tradeContainer.removeItemNoUpdate(0));
                pPlayer.getInventory().placeItemBackInInventory(this.tradeContainer.removeItemNoUpdate(1));
            }
        }
    }

    public void tryMoveItems(int pSelectedMerchantRecipe) {
        if (pSelectedMerchantRecipe >= 0 && this.getOffers().size() > pSelectedMerchantRecipe) {
            ItemStack itemstack = this.tradeContainer.getItem(0);
            if (!itemstack.isEmpty()) {
                if (!this.moveItemStackTo(itemstack, 3, 39, true)) {
                    return;
                }

                this.tradeContainer.setItem(0, itemstack);
            }

            ItemStack itemstack1 = this.tradeContainer.getItem(1);
            if (!itemstack1.isEmpty()) {
                if (!this.moveItemStackTo(itemstack1, 3, 39, true)) {
                    return;
                }

                this.tradeContainer.setItem(1, itemstack1);
            }

            if (this.tradeContainer.getItem(0).isEmpty() && this.tradeContainer.getItem(1).isEmpty()) {
                MerchantOffer merchantoffer = this.getOffers().get(pSelectedMerchantRecipe);
                this.moveFromInventoryToPaymentSlot(0, merchantoffer.getItemCostA());
                merchantoffer.getItemCostB().ifPresent(p_332192_ -> this.moveFromInventoryToPaymentSlot(1, p_332192_));
            }
        }
    }

    private void moveFromInventoryToPaymentSlot(int pPaymentSlotIndex, ItemCost pPayment) {
        for (int i = 3; i < 39; i++) {
            ItemStack itemstack = this.slots.get(i).getItem();
            if (!itemstack.isEmpty() && pPayment.test(itemstack)) {
                ItemStack itemstack1 = this.tradeContainer.getItem(pPaymentSlotIndex);
                if (itemstack1.isEmpty() || ItemStack.isSameItemSameComponents(itemstack, itemstack1)) {
                    int j = itemstack.getMaxStackSize();
                    int k = Math.min(j - itemstack1.getCount(), itemstack.getCount());
                    ItemStack itemstack2 = itemstack.copyWithCount(itemstack1.getCount() + k);
                    itemstack.shrink(k);
                    this.tradeContainer.setItem(pPaymentSlotIndex, itemstack2);
                    if (itemstack2.getCount() >= j) {
                        break;
                    }
                }
            }
        }
    }

    public void setOffers(MerchantOffers pOffers) {
        this.trader.overrideOffers(pOffers);
    }

    public MerchantOffers getOffers() {
        return this.trader.getOffers();
    }

    public boolean showProgressBar() {
        return this.showProgressBar;
    }
}