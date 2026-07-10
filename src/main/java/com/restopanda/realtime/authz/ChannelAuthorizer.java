package com.restopanda.realtime.authz;

import com.restopanda.commons.core.error.ForbiddenException;
import com.restopanda.commons.core.error.ValidationException;
import com.restopanda.realtime.channel.Channel;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns a caller's <em>requested</em> channel list into the concrete
 * {@link Channel}s they are allowed to receive — rejecting the whole request if
 * any single channel is out of scope. All-or-nothing keeps the ticket honest:
 * a minted ticket grants exactly the channels the caller could see at mint time.
 */
@Component
public class ChannelAuthorizer {

    /** Guard against a caller trying to open a firehose of channels on one ticket. */
    private static final int MAX_CHANNELS = 50;

    /**
     * Authorizes the requested channels for the caller.
     *
     * @param caller           the resolved caller
     * @param requestedChannels the raw channel strings the caller asked for
     * @return the parsed, authorized channels
     * @throws ValidationException if the list is empty/too large or a channel is
     *                             malformed
     * @throws ForbiddenException  if any requested channel is out of the caller's
     *                             scope (cross-tenant, cross-location, missing
     *                             entitlement, or guest-out-of-session)
     */
    public List<Channel> authorize(Caller caller, List<String> requestedChannels) {
        if (requestedChannels == null || requestedChannels.isEmpty()) {
            throw new ValidationException("realtime.channel.required", "channels", "at least one channel is required");
        }
        if (requestedChannels.size() > MAX_CHANNELS) {
            throw new ValidationException(
                    "realtime.channel.too_many", "channels", "too many channels requested (max " + MAX_CHANNELS + ")");
        }
        List<Channel> authorized = new ArrayList<>(requestedChannels.size());
        for (String raw : requestedChannels) {
            Channel channel;
            try {
                channel = Channel.parse(raw);
            } catch (IllegalArgumentException e) {
                throw new ValidationException("realtime.channel.malformed", "channels", "malformed channel: " + raw);
            }
            if (!caller.mayAccess(channel)) {
                // Do not leak which check failed (tenant/location/entitlement) —
                // one uniform rejection.
                throw new ForbiddenException("realtime.channel.forbidden", "not allowed to subscribe to " + raw);
            }
            authorized.add(channel);
        }
        return authorized;
    }
}
