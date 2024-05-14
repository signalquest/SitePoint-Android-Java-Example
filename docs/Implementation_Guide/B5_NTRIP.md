[Home](../index.md) / [Implementation Guide](B1_Implementation_Guide.md) / NTRIP

# NTRIP

## Overview

NTRIP (Network Transport of RTCM via Internet Protocol) is an internet protocol for streaming RTCM (Radio Technical Commission for Maritime Services) packets from a nearby base station. These RTCM messages are forwarded to the SitePoint to aid in acquiring a more accurate location solution.

The app handles setting up the NTRIP connection, while the SDK parses the RTCM messages, which can then be sent to the SitePoint.

Some NTRIP providers require the device's location to be sent upon connection, and others require regular location updates. These locations are assembled into GGA (Global Positioning System Fix Data) strings. The example app includes functions to create these strings in NtripGga.java.

### Connecting to an NTRIP Service

The example app demonstrates a simplistic approach to establishing a connection with an NTRIP provider.

Once the connection is requested, the response is passed into `parseAuthorized` method of the `NtripParser` class to parse the authorization request.

### Parsing Received RTCM Messages

After a successful connection, the `parseRtcm` method of the `NtripParser` class validates and filters the data, ignoring any messages that are not relevant for SitePoint aiding.

The parsed data is then available using the `next` method of the `NtripParser` class. For efficient writes, this data can have multiple messages, including messages that are split between returns from this method.

### Sending RTCM Messages to the SitePoint

Write the prepared RTCM data to the RTCM characteristic using the `writeCharacteristic` method of the `BluetoothGatt` class.

The SitePoint will receive these messages and begin using them to aid in solving a more accurate solution. When successful, and given good satellite view, the status should change from _3D_ to _float_ and then to _fix_, where the SitePoint will operate in its highest-accuracy mode.

### Monitoring NTRIP Aiding Quality

The `aidingQuality` array of booleans in the `Status` class monitors whether the SitePoint used the recent RTCM messages.

For example, in the SQ Survey app, a graphical indicator shows this aiding quality with either a solid or empty rectangle for each position. This can be helpful for diagnosing issues obtaining a higher accuracy.
