/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package server.maps;

import client.MapleCharacter;
import client.MapleClient;
import client.inventory.Item;
import client.inventory.MapleInventoryType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import tools.locks.MonitoredReentrantLock;
import net.SendOpcode;
import net.server.Server;
import server.MapleInventoryManipulator;
import server.MapleItemInformationProvider;
import tools.MaplePacketCreator;
import tools.Pair;
import tools.data.output.MaplePacketLittleEndianWriter;
import tools.locks.MonitoredLockType;

/**
 *
 * @author Matze
 * @author Ronan (concurrency protection)
 */
public class MaplePlayerShop extends AbstractMapleMapObject {
    private AtomicBoolean open = new AtomicBoolean(false);
    private MapleCharacter owner;
    private MapleCharacter[] visitors = new MapleCharacter[3];
    private List<MaplePlayerShopItem> items = new ArrayList<>();
    private String description;
    private int boughtnumber = 0;
    private List<String> bannedList = new ArrayList<>();
    private List<Pair<MapleCharacter, String>> chatLog = new LinkedList<>();
    private Map<Integer, Byte> chatSlot = new LinkedHashMap<>();
    private Lock visitorLock = new MonitoredReentrantLock(MonitoredLockType.VISITOR_PSHOP, true);

    public MaplePlayerShop(MapleCharacter owner, String description) {
        this.setPosition(owner.getPosition());
        this.owner = owner;
        this.description = description;
    }

    public int getChannel() {
        return owner.getClient().getChannel();
    }
    
    public int getMapId() {
        return owner.getMapId();
    }
    
    public boolean isOpen() {
        return open.get();
    }
    
    public void setOpen(boolean openShop) {
        open.set(openShop);
    }
    
    public boolean hasFreeSlot() {
        visitorLock.lock();
        try {
            return visitors[0] == null || visitors[1] == null || visitors[2] == null;
        } finally {
            visitorLock.unlock();
        }
    }

    public boolean isOwner(MapleCharacter c) {
        return owner.equals(c);
    }

    private void addVisitor(MapleCharacter visitor) {
        visitorLock.lock();
        try {
            for (int i = 0; i < 3; i++) {
                if (visitors[i] == null) {
                    visitors[i] = visitor;
                    visitor.setSlot(i);
                    this.broadcast(MaplePacketCreator.getPlayerShopNewVisitor(visitor, i + 1));
                    
                    if(i == 2) visitor.getMap().broadcastMessage(MaplePacketCreator.addCharBox(owner, 1));
                    break;
                }
            }
        } finally {
            visitorLock.unlock();
        }
    }

    public void forceRemoveVisitor(MapleCharacter visitor) {
        if (visitor == owner) {
            owner.getMap().removeMapObject(this);
            owner.setPlayerShop(null);
        }
        
        visitorLock.lock();
        try {
            for (int i = 0; i < 3; i++) {
                if (visitors[i] != null && visitors[i].getId() == visitor.getId()) {
                    visitors[i] = null;
                    visitor.setSlot(-1);
                    this.broadcast(MaplePacketCreator.getPlayerShopRemoveVisitor(i + 1));
                    return;
                }
            }
        } finally {
            visitorLock.unlock();
        }
    }
    
    public void removeVisitor(MapleCharacter visitor) {
        if (visitor == owner) {
            owner.getMap().removeMapObject(this);
            owner.setPlayerShop(null);
        } else {
            visitorLock.lock();
            try {
                for (int i = 0; i < 3; i++) {
                    if (visitors[i] != null && visitors[i].getId() == visitor.getId()) {
                        visitor.setSlot(-1);    //absolutely cant remove player slot for late players without dc'ing them... heh
                        
                        for(int j = i; j < 2; j++) {
                            if(visitors[j] != null) owner.announce(MaplePacketCreator.getPlayerShopRemoveVisitor(j + 1));
                            visitors[j] = visitors[j + 1];
                            if(visitors[j] != null) visitors[j].setSlot(j);
                        }
                        visitors[2] = null;
                        for(int j = i; j < 2; j++) {
                            if(visitors[j] != null) owner.announce(MaplePacketCreator.getPlayerShopNewVisitor(visitors[j], j + 1));
                        }
                        
                        this.broadcastRestoreToVisitors();
                        return;
                    }
                }
            } finally {
                visitorLock.unlock();
            }
            
            if(owner.getPlayerShop() != null) visitor.getMap().broadcastMessage(MaplePacketCreator.addCharBox(owner, 4));
        }
    }

    public boolean isVisitor(MapleCharacter visitor) {
        visitorLock.lock();
        try {
            return visitors[0] == visitor || visitors[1] == visitor || visitors[2] == visitor;
        } finally {
            visitorLock.unlock();
        }
    }

    public void addItem(MaplePlayerShopItem item) {
        synchronized (items) {
            items.add(item);
        }
    }

    public void removeItem(int item) {
        synchronized (items) {
            items.remove(item);
        }
    }

    private static boolean canBuy(MapleClient c, Item newItem) {
        return MapleInventoryManipulator.checkSpace(c, newItem.getItemId(), newItem.getQuantity(), newItem.getOwner()) && MapleInventoryManipulator.addFromDrop(c, newItem, false);
    }
    
    /**
     * no warnings for now o.o
     * @param c
     * @param item
     * @param quantity
     */
    public void buy(MapleClient c, int item, short quantity) {
        synchronized (items) {
            if (isVisitor(c.getPlayer())) {
                MaplePlayerShopItem pItem = items.get(item);
                Item newItem = pItem.getItem().copy();
                newItem.setQuantity(newItem.getQuantity());
                if (quantity < 1 || pItem.getBundles() < 1 || newItem.getQuantity() > pItem.getBundles() || !pItem.isExist()) {
                    return;
                } else if (newItem.getInventoryType().equals(MapleInventoryType.EQUIP) && newItem.getQuantity() > 1) {
                    return;
                }
                synchronized (c.getPlayer()) {
                    int price = (int) Math.min((long)pItem.getPrice() * quantity, Integer.MAX_VALUE);
                    
                    if (c.getPlayer().getMeso() >= price) {
                        if (canBuy(c, newItem)) {
                            c.getPlayer().gainMeso(-price, false);
                            
                            announceItemSold(newItem, price);   // idea thanks to vcoc
                            owner.gainMeso(price, true);
                            
                            pItem.setBundles((short) (pItem.getBundles() - quantity));
                            if (pItem.getBundles() < 1) {
                                pItem.setDoesExist(false);
                                if (++boughtnumber == items.size()) {
                                    owner.setPlayerShop(null);
                                    this.setOpen(false);
                                    this.closeShop();
                                    owner.dropMessage(1, "Your items are sold out, and therefore your shop is closed.");
                                }
                            }
                        } else {
                            c.getPlayer().dropMessage(1, "Your inventory is full. Please clean a slot before buying this item.");
                        }
                    }
                }
            }
        }
    }
    
    private void announceItemSold(Item item, int mesos) {
        String qtyStr = (item.getQuantity() > 1) ? " (qty. " + item.getQuantity() + ")" : "";
        
        owner.dropMessage(6, "[PLAYER SHOP] Item '" + MapleItemInformationProvider.getInstance().getName(item.getItemId()) + "'" + qtyStr + " has been sold for " + mesos + " mesos.");
    }

    public void broadcastToVisitors(final byte[] packet) {
        visitorLock.lock();
        try {
            for (int i = 0; i < 3; i++) {
                if (visitors[i] != null) {
                    visitors[i].getClient().announce(packet);
                }
            }
        } finally {
            visitorLock.unlock();
        }
    }
    
    public void broadcastRestoreToVisitors() {
        visitorLock.lock();
        try {
            for (int i = 0; i < 3; i++) {
                if (visitors[i] != null) {
                    visitors[i].getClient().announce(MaplePacketCreator.getPlayerShopRemoveVisitor(i + 1));
                }
            }
            
            for (int i = 0; i < 3; i++) {
                if (visitors[i] != null) {
                    visitors[i].getClient().announce(MaplePacketCreator.getPlayerShop(this, false));
                }
            }
            
            recoverChatLog();
        } finally {
            visitorLock.unlock();
        }
    }

    public void removeVisitors() {
        List<MapleCharacter> visitorList = new ArrayList<>(3);
        
        visitorLock.lock();
        try {
            try {
                for (int i = 0; i < 3; i++) {
                    if (visitors[i] != null) {
                        visitors[i].getClient().announce(MaplePacketCreator.shopErrorMessage(10, 1));
                        visitorList.add(visitors[i]);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } finally {
            visitorLock.unlock();
        }
        
        for(MapleCharacter mc : visitorList) forceRemoveVisitor(mc);
        if (owner != null) {
            forceRemoveVisitor(owner);
        }
    }

    public static byte[] shopErrorMessage(int error, int type) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();
        mplew.writeShort(SendOpcode.PLAYER_INTERACTION.getValue());
        mplew.write(0x0A);
        mplew.write(type);
        mplew.write(error);
        return mplew.getPacket();
    }

    public void broadcast(final byte[] packet) {
        if (owner.getClient() != null && owner.getClient().getSession() != null) {
            owner.getClient().announce(packet);
        }
        broadcastToVisitors(packet);
    }

    private byte getVisitorSlot(MapleCharacter chr) {
        byte s = 0;
        for (MapleCharacter mc : getVisitors()) {
            s++;
            if (mc != null) {
                if (mc.getName().equalsIgnoreCase(chr.getName())) {
                    break;
                }
            } else if (s == 3) {
                s = 0;
            }
        }
        
        return s;
    }
    
    public void chat(MapleClient c, String chat) {
        byte s = getVisitorSlot(c.getPlayer());
        
        synchronized(chatLog) {
            chatLog.add(new Pair<>(c.getPlayer(), chat));
            if(chatLog.size() > 25) chatLog.remove(0);
            chatSlot.put(c.getPlayer().getId(), s);
        }
        
        broadcast(MaplePacketCreator.getPlayerShopChat(c.getPlayer(), chat, s));
    }
    
    private void recoverChatLog() {
        synchronized(chatLog) {
            for(Pair<MapleCharacter, String> it : chatLog) {
                MapleCharacter chr = it.getLeft();
                Byte pos = chatSlot.get(chr.getId());
                
                broadcastToVisitors(MaplePacketCreator.getPlayerShopChat(chr, it.getRight(), pos));
            }
        }
    }
    
    private void clearChatLog() {
        synchronized(chatLog) {
            chatLog.clear();
        }
    }
    
    public void closeShop() {
        owner.getMap().broadcastMessage(MaplePacketCreator.removeCharBox(owner));
        clearChatLog();
        removeVisitors();
    }

    public void sendShop(MapleClient c) {
        visitorLock.lock();
        try {
            c.announce(MaplePacketCreator.getPlayerShop(this, isOwner(c.getPlayer())));
        } finally {
            visitorLock.unlock();
        }
    }

    public MapleCharacter getOwner() {
        return owner;
    }

    public MapleCharacter[] getVisitors() {
        visitorLock.lock();
        try {
            MapleCharacter[] copy = new MapleCharacter[3];
            for(int i = 0; i < visitors.length; i++) copy[i] = visitors[i];
                    
            return copy;
        } finally {
            visitorLock.unlock();
        }
    }

    public List<MaplePlayerShopItem> getItems() {
        synchronized (items) {
            return Collections.unmodifiableList(items);
        }
    }
    
    public boolean hasItem(int itemid) {
        for(MaplePlayerShopItem mpsi : getItems()) {
            if(mpsi.getItem().getItemId() == itemid && mpsi.isExist() && mpsi.getBundles() > 0) {
                return true;
            }
        }
        
        return false;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void banPlayer(String name) {
        if (!bannedList.contains(name)) {
            bannedList.add(name);
        }
        
        MapleCharacter target = null;
        visitorLock.lock();
        try {
            for (int i = 0; i < 3; i++) {
                if (visitors[i] != null && visitors[i].getName().equals(name)) {
                    target = visitors[i];
                    break;
                }
            }
        } finally {
            visitorLock.unlock();
        }
        
        if(target != null) {
            target.getClient().announce(MaplePacketCreator.shopErrorMessage(5, 1));
            removeVisitor(target);
        }
    }

    public boolean isBanned(String name) {
        return bannedList.contains(name);
    }
    
    public synchronized boolean visitShop(MapleCharacter chr) {
        if(!open.get()) {
            chr.dropMessage(1, "This store is not yet open.");
            return false;
        }
        
        if (this.isBanned(chr.getName())) {
            chr.dropMessage(1, "You have been banned from this store.");
            return false;
        }
        
        visitorLock.lock();
        try {
            if (this.hasFreeSlot() && !this.isVisitor(chr)) {
                this.addVisitor(chr);
                chr.setPlayerShop(this);
                this.sendShop(chr.getClient());

                return true;
            }

            return false;
        } finally {
            visitorLock.unlock();
        }
    }
    
    public List<MaplePlayerShopItem> sendAvailableBundles(int itemid) {
        List<MaplePlayerShopItem> list = new LinkedList<>();
        List<MaplePlayerShopItem> all = new ArrayList<>();
        
        synchronized (items) {
            for(MaplePlayerShopItem mpsi : items) all.add(mpsi);
        }
        
        for(MaplePlayerShopItem mpsi : all) {
            if(mpsi.getItem().getItemId() == itemid && mpsi.getBundles() > 0 && mpsi.isExist()) {
                list.add(mpsi);
            }
        }
        return list;
    }

    @Override
    public void sendDestroyData(MapleClient client) {
        client.announce(MaplePacketCreator.removeCharBox(owner));
    }

    @Override
    public void sendSpawnData(MapleClient client) {
        client.announce(MaplePacketCreator.addCharBox(owner, 4));
    }

    @Override
    public MapleMapObjectType getType() {
        return MapleMapObjectType.SHOP;
    }
}