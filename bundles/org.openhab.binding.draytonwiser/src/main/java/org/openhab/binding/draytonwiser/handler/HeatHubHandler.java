/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.draytonwiser.handler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.draytonwiser.DraytonWiserBindingConstants;
import org.openhab.binding.draytonwiser.internal.DraytonWiserItemUpdateListener;
import org.openhab.binding.draytonwiser.internal.config.Device;
import org.openhab.binding.draytonwiser.internal.config.Domain;
import org.openhab.binding.draytonwiser.internal.config.HeatingChannel;
import org.openhab.binding.draytonwiser.internal.config.HotWater;
import org.openhab.binding.draytonwiser.internal.config.Room;
import org.openhab.binding.draytonwiser.internal.config.RoomStat;
import org.openhab.binding.draytonwiser.internal.config.Schedule;
import org.openhab.binding.draytonwiser.internal.config.SmartPlug;
import org.openhab.binding.draytonwiser.internal.config.SmartValve;
import org.openhab.binding.draytonwiser.internal.config.Station;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * The {@link HeatHubHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Andrew Schofield - Initial contribution
 */
@NonNullByDefault
public class HeatHubHandler extends BaseBridgeHandler {

    @Nullable
    protected ScheduledFuture<?> refreshJob;

    private final Logger logger = LoggerFactory.getLogger(HeatHubHandler.class);
    private HttpClient httpClient;
    private Gson gson;

    private List<DraytonWiserItemUpdateListener> itemListeners = new ArrayList<>();

    @Nullable
    private Domain domain;

    public HeatHubHandler(Bridge thing) {
        super(thing);
        httpClient = new HttpClient();

        // attempt to prevent EOFException as per https://bugs.eclipse.org/bugs/show_bug.cgi?id=440729
        httpClient.setMaxConnectionsPerDestination(3);
        httpClient.setConnectTimeout(10000);
        httpClient.setAddressResolutionTimeout(10000);

        gson = new Gson();

        try {
            httpClient.start();
        } catch (Exception ex) {
            logger.error("{}", ex.getMessage());
        }
    }

    @Override
    public void dispose() {
        if (httpClient != null) {
            httpClient.destroy();
        }

        if (refreshJob != null) {
            refreshJob.cancel(true);
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }

    @Override
    public void initialize() {
        logger.debug("Initializing Drayton Wiser Heat Hub handler");
        Device device = getExtendedDeviceProperties(0);
        if (device != null) {
            Map<String, String> properties = new HashMap<>();
            properties.put("Device Type", device.getProductIdentifier());
            properties.put("Firmware Version", device.getActiveFirmwareVersion());
            properties.put("Manufacturer", device.getManufacturer());
            properties.put("Model", device.getModelIdentifier());
            getThing().setProperties(properties);
        }

        startAutomaticRefresh();
        refresh();
    }

    private synchronized void startAutomaticRefresh() {
        Thing thing = getThing();
        if (thing != null) {
            refreshJob = scheduler.scheduleWithFixedDelay(() -> {
                logger.debug("Refreshing devices");
                refresh();
                logger.debug("Finished refreshing devices");
            }, 0, ((java.math.BigDecimal) thing.getConfiguration().get(DraytonWiserBindingConstants.REFRESH_INTERVAL))
                    .intValue(), TimeUnit.SECONDS);
        }
    }

    private void refresh() {
        try {
            domain = getDomain();
            notifyListeners();
        } catch (Exception e) {
            logger.debug("Exception occurred during execution: {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    private synchronized void notifyListeners() {
        for (DraytonWiserItemUpdateListener itemListener : itemListeners) {
            itemListener.onItemUpdate();
        }
    }

    public @Nullable Domain getDomain() {
        ContentResponse response = sendMessageToHeatHub(DraytonWiserBindingConstants.DOMAIN_ENDPOINT, HttpMethod.GET);

        if (response == null) {
            return null;
        }

        try {
            Domain domain = gson.fromJson(response.getContentAsString(), Domain.class);
            return domain;
        } catch (JsonSyntaxException e) {
            logger.debug("Could not parse Json content: {}", e.getMessage(), e);
            return null;
        }
    }

    public List<RoomStat> getRoomStats() {
        if (domain == null) {
            return new ArrayList<RoomStat>();
        }

        return domain.getRoomStat();
    }

    public List<SmartValve> getSmartValves() {
        if (domain == null) {
            return new ArrayList<SmartValve>();
        }

        return domain.getSmartValve();
    }

    public List<SmartPlug> getSmartPlugs() {
        if (domain == null) {
            return new ArrayList<SmartPlug>();
        }

        return domain.getSmartPlug();
    }

    public List<Room> getRooms() {
        if (domain == null) {
            return new ArrayList<Room>();
        }

        return domain.getRoom();
    }

    public @Nullable Room getRoom(String name) {
        if (domain == null) {
            return null;
        }

        for (Room room : domain.getRoom()) {
            if (room.getName().toLowerCase().equals(name.toLowerCase())) {
                return room;
            }
        }

        return null;
    }

    public @Nullable RoomStat getRoomStat(String serialNumber) {
        if (domain == null) {
            return null;
        }

        Integer id = getIdFromSerialNumber(serialNumber);

        if (id == null) {
            return null;
        }

        for (RoomStat roomStat : domain.getRoomStat()) {
            if (roomStat.getId().equals(id)) {
                return roomStat;
            }
        }

        return null;
    }

    public @Nullable RoomStat getRoomStat(int id) {
        if (domain == null) {
            return null;
        }

        for (RoomStat roomStat : domain.getRoomStat()) {
            if (roomStat.getId().equals(id)) {
                return roomStat;
            }
        }

        return null;
    }

    public @Nullable SmartValve getSmartValve(String serialNumber) {
        if (domain == null) {
            return null;
        }

        Integer id = getIdFromSerialNumber(serialNumber);

        if (id == null) {
            return null;
        }

        for (SmartValve smartValve : domain.getSmartValve()) {
            if (smartValve.getId().equals(id)) {
                return smartValve;
            }
        }

        return null;
    }

    public @Nullable SmartPlug getSmartPlug(String serialNumber) {
        if (domain == null) {
            return null;
        }

        Integer id = getIdFromSerialNumber(serialNumber);

        if (id == null) {
            return null;
        }

        for (SmartPlug smartPlug : domain.getSmartPlug()) {
            if (smartPlug.getId().equals(id)) {
                return smartPlug;
            }
        }

        return null;
    }

    public @Nullable Device getExtendedDeviceProperties(int id) {
        if (domain == null) {
            return null;
        }

        for (Device device : domain.getDevice()) {
            if (device.getId().equals(id)) {
                return device;
            }
        }

        return null;
    }

    public org.openhab.binding.draytonwiser.internal.config.@Nullable System getSystem() {
        if (domain == null) {
            return null;
        }

        return domain.getSystem();
    }

    public @Nullable Station getStation() {
        Station station = null;
        ContentResponse response = sendMessageToHeatHub(DraytonWiserBindingConstants.STATION_ENDPOINT, HttpMethod.GET);

        if (response == null) {
            return null;
        }

        station = gson.fromJson(response.getContentAsString(), Station.class);
        return station;
    }

    public List<HeatingChannel> getHeatingChannels() {
        if (domain == null) {
            return new ArrayList<HeatingChannel>();
        }

        return domain.getHeatingChannel();
    }

    public List<HotWater> getHotWater() {
        if (domain == null) {
            return new ArrayList<HotWater>();
        }

        return domain.getHotWater();
    }

    @Nullable
    public Room getRoomForDeviceId(Integer id) {
        refresh();
        if (domain == null) {
            return null;
        }

        for (Room room : domain.getRoom()) {
            if (room != null) {
                if (room.getRoomStatId() != null && room.getRoomStatId().equals(id)) {
                    return room;
                }

                List<Integer> trvs = room.getSmartValveIds();
                if (trvs != null) {
                    for (Integer itrv : trvs) {
                        if (itrv.equals(id)) {
                            return room;
                        }
                    }
                }
            }
        }

        return null;
    }

    public @Nullable Schedule getSchedule(Integer scheduleId) {
        if (domain == null) {
            return null;
        }

        for (Schedule schedule : domain.getSchedule()) {
            if (schedule != null) {
                if (schedule.getId() != null && schedule.getId().equals(scheduleId)) {
                    return schedule;
                }
            }
        }

        return null;
    }

    public synchronized boolean registerItemListener(DraytonWiserItemUpdateListener listener) {
        boolean result = false;
        result = !(itemListeners.contains(listener)) ? itemListeners.add(listener) : false;
        return result;
    }

    public synchronized boolean unregisterItemListener(DraytonWiserItemUpdateListener listener) {
        return itemListeners.remove(listener);
    }

    public void setRoomSetPoint(String roomName, Integer setPoint) {
        Room room = getRoom(roomName);
        if (room == null) {
            return;
        }

        String payload = "{\"RequestOverride\":{\"Type\":\"Manual\", \"SetPoint\":" + setPoint + "}}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.ROOMS_ENDPOINT + room.getId().toString(), "PATCH", payload);
        refresh();
    }

    public void setRoomManualMode(String roomName, Boolean manualMode) {
        Room room = getRoom(roomName);
        if (room == null) {
            return;
        }

        String payload = "{\"Mode\":\"" + (manualMode ? "Manual" : "Auto") + "\"}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.ROOMS_ENDPOINT + room.getId().toString(), "PATCH", payload);
        payload = "{\"RequestOverride\":{\"Type\":\"None\",\"Originator\" :\"App\",\"DurationMinutes\":0,\"SetPoint\":0}}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.ROOMS_ENDPOINT + room.getId().toString(), "PATCH", payload);
        refresh();
    }

    public void setRoomWindowStateDetection(String roomName, Boolean windowStateDetection) {
        Room room = getRoom(roomName);
        if (room == null) {
            return;
        }

        String payload = windowStateDetection.toString().toLowerCase();
        sendMessageToHeatHub(
                DraytonWiserBindingConstants.ROOMS_ENDPOINT + room.getId().toString() + "/WindowDetectionActive",
                "PATCH", payload);
        refresh();
    }

    public void setRoomBoostActive(String roomName, Integer setPoint, Integer duration) {
        Room room = getRoom(roomName);
        if (room == null) {
            return;
        }

        String payload = "{\"RequestOverride\":{\"Type\":\"Manual\",\"Originator\" :\"App\",\"DurationMinutes\":"
                + duration + ",\"SetPoint\":" + setPoint + "}}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.ROOMS_ENDPOINT + room.getId().toString(), "PATCH", payload);
        refresh();
    }

    public void setRoomSchedule(String roomName, String scheduleJSON) {
        Room room = getRoom(roomName);
        if (room == null) {
            return;
        }

        String payload = scheduleJSON;
        sendMessageToHeatHub(DraytonWiserBindingConstants.SCHEDULES_ENDPOINT + room.getScheduleId().toString(), "PATCH",
                payload);
        refresh();
    }

    public void setRoomBoostInactive(String roomName) {
        Room room = getRoom(roomName);
        if (room == null) {
            return;
        }

        String payload = "{\"RequestOverride\":{\"Type\":\"None\",\"Originator\" :\"App\",\"DurationMinutes\":0,\"SetPoint\":0}}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.ROOMS_ENDPOINT + room.getId().toString(), "PATCH", payload);
        refresh();
    }

    public void setHotWaterManualMode(Boolean manualMode) {
        String payload = "{\"Mode\":\"" + (manualMode ? "Manual" : "Auto") + "\"}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.HOTWATER_ENDPOINT + "2", "PATCH", payload);
        payload = "{\"RequestOverride\":{\"Type\":\"None\",\"Originator\" :\"App\",\"DurationMinutes\":0,\"SetPoint\":0}}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.HOTWATER_ENDPOINT + "2", "PATCH", payload);
        refresh();
    }

    public void setHotWaterSetPoint(Integer setPoint) {
        String payload = "{\"RequestOverride\":{\"Type\":\"Manual\", \"SetPoint\":" + setPoint + "}}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.HOTWATER_ENDPOINT + "2", "PATCH", payload);
        refresh();
    }

    public void setHotWaterBoostActive(Integer duration) {
        String payload = "{\"RequestOverride\":{\"Type\":\"Manual\",\"Originator\" :\"App\",\"DurationMinutes\":"
                + duration + ",\"SetPoint\":1100}}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.HOTWATER_ENDPOINT + "2", "PATCH", payload);
        refresh();
    }

    public void setHotWaterBoostInactive() {
        String payload = "{\"RequestOverride\":{\"Type\":\"None\",\"Originator\" :\"App\",\"DurationMinutes\":0,\"SetPoint\":0}}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.HOTWATER_ENDPOINT + "2", "PATCH", payload);
        refresh();
    }

    public void setAwayMode(Boolean awayMode) {
        Integer setPoint = ((java.math.BigDecimal) thing.getConfiguration()
                .get(DraytonWiserBindingConstants.AWAY_MODE_SETPOINT)).intValue() * 10;

        String payload = "{\"Type\":" + (awayMode ? "2" : "0") + ", \"setPoint\":"
                + (awayMode ? setPoint.toString() : "0") + "}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.SYSTEM_ENDPOINT + "RequestOverride", "PATCH", payload);
        payload = "{\"Type\":" + (awayMode ? "2" : "0") + ", \"setPoint\":" + (awayMode ? "-200" : "0") + "}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.HOTWATER_ENDPOINT + "2/RequestOverride", "PATCH", payload);
        refresh();
    }

    public void setDeviceLocked(Integer deviceId, Boolean locked) {
        String payload = locked ? "true" : "false";
        sendMessageToHeatHub(DraytonWiserBindingConstants.DEVICE_ENDPOINT + deviceId + "/DeviceLockEnabled", "PATCH",
                payload);
        refresh();
    }

    public void setEcoMode(Boolean ecoMode) {
        String payload = "{\"EcoModeEnabled\":" + ecoMode + "}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.SYSTEM_ENDPOINT, "PATCH", payload);
        refresh();
    }

    public void setSmartPlugSchedule(Integer id, String scheduleJSON) {
        String payload = scheduleJSON;
        sendMessageToHeatHub(DraytonWiserBindingConstants.SCHEDULES_ENDPOINT + id.toString(), "PATCH", payload);
        refresh();
    }

    public void setSmartPlugManualMode(Integer id, Boolean manualMode) {
        String payload = "{\"Mode\":\"" + (manualMode ? "Manual" : "Auto") + "\"}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.SMARTPLUG_ENDPOINT + id.toString(), "PATCH", payload);
        refresh();
    }

    public void setSmartPlugOutputState(Integer id, Boolean outputState) {
        String payload = "{\"RequestOutput\":\"" + (outputState ? "On" : "Off") + "\"}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.SMARTPLUG_ENDPOINT + id.toString(), "PATCH", payload);
        // update the state after the heathub has had time to react
        scheduler.schedule(() -> refresh(), 5, TimeUnit.SECONDS);
    }

    public void setSmartPlugAwayAction(Integer id, Boolean awayAction) {
        String payload = "{\"AwayAction\":\"" + (awayAction ? "Off" : "NoChange") + "\"}";
        sendMessageToHeatHub(DraytonWiserBindingConstants.SMARTPLUG_ENDPOINT + id.toString(), "PATCH", payload);
        refresh();
    }

    synchronized private @Nullable ContentResponse sendMessageToHeatHub(String path, HttpMethod method) {
        return sendMessageToHeatHub(path, method.asString(), "");
    }

    synchronized private @Nullable ContentResponse sendMessageToHeatHub(String path, HttpMethod method,
            String content) {
        return sendMessageToHeatHub(path, method.asString(), content);
    }

    synchronized private @Nullable ContentResponse sendMessageToHeatHub(String path, String method, String content) {
        try {
            logger.debug("Sending message to heathub: " + path);
            String address = (String) getConfig().get(DraytonWiserBindingConstants.ADDRESS);
            String secret = (String) getConfig().get(DraytonWiserBindingConstants.SECRET);
            StringContentProvider contentProvider = new StringContentProvider(content);
            ContentResponse response = httpClient.newRequest("http://" + address + "/" + path).method(method)
                    .header("SECRET", secret).content(contentProvider).timeout(10, TimeUnit.SECONDS).send();
            if (response.getStatus() == 200) {
                updateStatus(ThingStatus.ONLINE);
                return response;
            } else if (response.getStatus() == 401) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Invalid authorization token");
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
            }
        } catch (TimeoutException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Heathub didn't repond in time");
            logger.debug(e.getMessage(), e);
        } catch (ExecutionException e) {
            logger.debug(e.getMessage(), e);
        } catch (Exception e) {
            logger.debug(e.getMessage(), e);
        }

        return null;
    }

    private @Nullable Integer getIdFromSerialNumber(String serialNumber) {
        if (domain == null) {
            return null;
        }

        for (Device device : domain.getDevice()) {
            if (device.getSerialNumber() != null
                    && device.getSerialNumber().toLowerCase().equals(serialNumber.toLowerCase())) {
                return device.getId();
            }
        }

        return null;
    }
}
