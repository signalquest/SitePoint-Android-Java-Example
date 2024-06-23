[Home](../index.md) / [Getting Started](../index.md#getting-started) / Android Java Example App

# Android Java Example App

The [SitePoint Android Java Example App source code](https://github.com/signalquest/SitePoint-Android-Java-Example) demonstrates a minimal implementation of the SitePoint SDK for interfacing with SitePoint devices.

## Source Code

This implementation focuses on essential, required code, omitting error handling and other best practices for clarity. The example app includes additional Javadoc documentation, and additional inline comments, which may provide helpful information during development.

### Sample Commented Code:

```java
/**
 * Scans and creates SitePoints from scan results.
 *
 * @param sitePointHandler listens for SitePoint scan results
 */
public void startScanning(SitePointHandler sitePointHandler) {
    if (disabled()) {
        return;
      }
      ScanSettings scanSettings =
          new ScanSettings.Builder()
              .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
              .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
              .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
              .build();
    ...
```

<hr>

## Next Steps

For detailed specifications of the SitePoint device, refer to the [SitePoint Datasheet](A3_SitePoint_Datasheet.md).

