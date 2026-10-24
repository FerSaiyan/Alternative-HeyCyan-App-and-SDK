#import "GlassesWiFiHandler.h"
#import <QCSDK/QCSDKCmdCreator.h>
#import "QCCentralManager.h"

@interface GlassesWiFiHandler ()

@property (nonatomic, assign) GlassesWiFiHandlerState state;
@property (nonatomic, copy) NSString *currentStatus;
@property (nonatomic, copy) NSString *glassesSSID;
@property (nonatomic, copy) NSString *glassesPassword;
@property (nonatomic, copy) GlassesWiFiHandlerStatusCallback statusCallback;
@property (nonatomic, copy) GlassesWiFiHandlerCredentialsCallback credentialsCallback;
@property (nonatomic, copy) GlassesWiFiHandlerConnectionCallback connectionCallback;
@property (nonatomic, assign) NSInteger manualJoinRounds;

@end

@implementation GlassesWiFiHandler

+ (instancetype)sharedHandler {
    static GlassesWiFiHandler *sharedInstance = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        sharedInstance = [[GlassesWiFiHandler alloc] init];
    });
    return sharedInstance;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _state = GlassesWiFiHandlerStateIdle;
        _currentStatus = @"Ready to connect";
    }
    return self;
}

#pragma mark - Public Methods

- (void)requestWiFiCredentialsWithStatusCallback:(GlassesWiFiHandlerStatusCallback)statusCallback
                                     completion:(GlassesWiFiHandlerCredentialsCallback)completion {

    if (self.state != GlassesWiFiHandlerStateIdle) {
        NSError *error = [NSError errorWithDomain:@"GlassesWiFiHandler"
                                             code:-1
                                         userInfo:@{NSLocalizedDescriptionKey: @"WiFi handler is busy"}];
        completion(@"", @"", error);
        return;
    }

    if (![QCCentralManager shared].connectedPeripheral) {
        NSError *error = [NSError errorWithDomain:@"GlassesWiFiHandler"
                                             code:-2
                                         userInfo:@{NSLocalizedDescriptionKey: @"Glasses device not connected via Bluetooth"}];
        completion(@"", @"", error);
        return;
    }

    self.state = GlassesWiFiHandlerStateRequestingCredentials;
    self.statusCallback = statusCallback;
    self.credentialsCallback = completion;

    [self updateStatus:@"Requesting WiFi credentials from glasses..." preview:nil];

    [QCSDKCmdCreator openWifiWithMode:QCOperatorDeviceModeTransfer success:^(NSString *ssid, NSString *password) {
        self.glassesSSID = ssid ?: @"";
        self.glassesPassword = @"123456789"; // Force correct password - glasses return wrong one

        [self updateStatus:[NSString stringWithFormat:@"Received credentials: %@", ssid ?: @"<unknown>"] preview:nil];

        self.credentialsCallback(self.glassesSSID, self.glassesPassword, nil);
        self.credentialsCallback = nil;

    } fail:^(NSInteger code) {
        NSError *error = [NSError errorWithDomain:@"GlassesWiFiHandler"
                                             code:code
                                         userInfo:@{NSLocalizedDescriptionKey: [NSString stringWithFormat:@"Failed to get WiFi credentials (code: %ld)", (long)code]}];

        [self updateStatus:[NSString stringWithFormat:@"Failed to get credentials (code: %ld)", (long)code] preview:nil];

        self.credentialsCallback(@"", @"", error);
        self.credentialsCallback = nil;

        self.state = GlassesWiFiHandlerStateFailed;
    }];
}

- (void)connectToGlassesWiFi:(NSString *)ssid
                    password:(NSString *)password
              statusCallback:(GlassesWiFiHandlerStatusCallback)statusCallback
                   completion:(GlassesWiFiHandlerConnectionCallback)completion {

    if (self.state != GlassesWiFiHandlerStateIdle) {
        NSError *error = [NSError errorWithDomain:@"GlassesWiFiHandler"
                                             code:-1
                                         userInfo:@{NSLocalizedDescriptionKey: @"WiFi handler is busy"}];
        completion(NO, @"", error);
        return;
    }

    self.state = GlassesWiFiHandlerStateConfiguringWiFi;
    self.glassesSSID = ssid;
    self.glassesPassword = password;
    self.statusCallback = statusCallback;
    self.connectionCallback = completion;

#if CYAN_FREE_PROVISIONING
    // Free provisioning cannot sign the Hotspot entitlement, so the user joins the hotspot in Settings.
    self.state = GlassesWiFiHandlerStateConnecting;
    self.manualJoinRounds = 0;
    [self updateStatus:[NSString stringWithFormat:@"📶 Open Settings ▸ Wi-Fi and join:\n%@\nPassword: %@", ssid, password.length > 0 ? password : @"(none)"] preview:nil];
    [self testConnection];
#else
    [self updateStatus:@"Configuring WiFi connection..." preview:nil];

    NEHotspotConfiguration *configuration;
    if (password && password.length > 0) {
        configuration = [[NEHotspotConfiguration alloc] initWithSSID:ssid passphrase:password isWEP:NO];
    } else {
        configuration = [[NEHotspotConfiguration alloc] initWithSSID:ssid];
    }

    configuration.joinOnce = YES;

    [[NEHotspotConfigurationManager sharedManager] applyConfiguration:configuration completionHandler:^(NSError * _Nullable error) {
        dispatch_async(dispatch_get_main_queue(), ^{
            if (!error) {
                [self updateStatus:@"WiFi configured. Testing connection..." preview:nil];
                self.state = GlassesWiFiHandlerStateConnecting;

                // Wait for connection and test
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(5.0 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
                    [self testConnection];
                });

            } else {
                [self updateStatus:[NSString stringWithFormat:@"WiFi configuration failed: %@", error.localizedDescription] preview:nil];

                NSError *handlerError = [NSError errorWithDomain:@"GlassesWiFiHandler"
                                                           code:error.code
                                                       userInfo:error.userInfo];

                self.connectionCallback(NO, @"", handlerError);
                self.connectionCallback = nil;

                self.state = GlassesWiFiHandlerStateFailed;
            }
        });
    }];
#endif
}

- (void)cancelCurrentOperation {
    [self reset];
}

- (void)reset {
    self.state = GlassesWiFiHandlerStateIdle;
    self.currentStatus = @"Ready to connect";
    self.glassesSSID = @"";
    self.glassesPassword = @"";
    self.statusCallback = nil;
    self.credentialsCallback = nil;
    self.connectionCallback = nil;
}

#pragma mark - Private Methods

- (void)updateStatus:(NSString *)status preview:(UIImage *)previewImage {
    self.currentStatus = status;

    if (self.statusCallback) {
        self.statusCallback(self.state, status, previewImage);
    }
}

- (void)testConnection {
    [self updateStatus:@"Testing connection to glasses..." preview:nil];

    // Common glasses IPs to test
    NSArray *possibleIPs = @[
        @"192.168.43.1", @"192.168.4.1", @"192.168.31.1", @"192.168.1.1",
        @"192.168.0.1", @"192.168.100.1", @"192.168.123.1", @"192.168.137.1",
        @"10.0.0.1", @"172.20.10.1"
    ];

    [self testIPs:possibleIPs index:0];
}

- (void)testIPs:(NSArray *)ips index:(NSInteger)index {
    if (index >= ips.count) {
#if CYAN_FREE_PROVISIONING
        // Keep scanning while the user joins the hotspot manually.
        const NSInteger maxManualJoinRounds = 6;
        if (self.manualJoinRounds < maxManualJoinRounds) {
            self.manualJoinRounds += 1;
            [self updateStatus:[NSString stringWithFormat:@"📶 Join %@ in Settings ▸ Wi-Fi. Waiting (%ld/%ld)...", self.glassesSSID, (long)self.manualJoinRounds, (long)maxManualJoinRounds] preview:nil];
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(3.0 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
                if (self.state == GlassesWiFiHandlerStateConnecting) {
                    [self testIPs:ips index:0];
                }
            });
            return;
        }
#endif
        [self updateStatus:@"Could not find glasses on any known IP address" preview:nil];

        NSError *error = [NSError errorWithDomain:@"GlassesWiFiHandler"
                                             code:-3
                                         userInfo:@{NSLocalizedDescriptionKey: @"Could not establish connection to glasses device"}];

        self.connectionCallback(NO, @"", error);
        self.connectionCallback = nil;

        self.state = GlassesWiFiHandlerStateFailed;
        return;
    }

    NSString *currentIP = ips[index];
    NSString *testURL = [NSString stringWithFormat:@"http://%@/files/media.config", currentIP];

    [self updateStatus:[NSString stringWithFormat:@"Testing %@ (%ld/%lu)...", currentIP, (long)index + 1, (unsigned long)ips.count] preview:nil];

    NSURLRequest *request = [NSURLRequest requestWithURL:[NSURL URLWithString:testURL]
                                            cachePolicy:NSURLRequestReloadIgnoringLocalCacheData
                                        timeoutInterval:3.0];

    [[[NSURLSession sharedSession] dataTaskWithRequest:request completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        dispatch_async(dispatch_get_main_queue(), ^{
            if (!error && ((NSHTTPURLResponse *)response).statusCode == 200) {
                [self updateStatus:[NSString stringWithFormat:@"Connected to glasses at %@", currentIP] preview:nil];

                self.state = GlassesWiFiHandlerStateConnected;
                self.connectionCallback(YES, currentIP, nil);
                self.connectionCallback = nil;

            } else {
                // Try next IP
                [self testIPs:ips index:index + 1];
            }
        });
    }] resume];
}

@end