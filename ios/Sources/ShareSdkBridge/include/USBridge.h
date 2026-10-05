#import <Foundation/Foundation.h>
NS_ASSUME_NONNULL_BEGIN
@interface USFileStream : NSObject
+ (nullable instancetype)openURL:(NSURL *)url destination:(BOOL)destination error:(NSError **)error NS_SWIFT_NAME(open(_:destination:));
- (void)close;
@end
/// Implementation bridge. Applications use the Swift ShareSdk services.
@interface USBridge : NSObject
@property(nonatomic, copy, nullable) void (^event)(NSDictionary<NSString *, id> *);
- (instancetype)initWithKeychainService:(NSString *)service;
- (void)create:(NSDictionary<NSString *, id> *)config completion:(void (^)(NSDictionary<NSString *, id> *))completion;
- (void)submit:(NSInteger)command arguments:(NSDictionary<NSString *, id> *)arguments completion:(void (^)(NSDictionary<NSString *, id> *))completion;
- (nullable NSArray<NSDictionary<NSString *, id> *> *)snapshot:(BOOL)trusted error:(NSError **)error;
- (NSUInteger)state;
- (void)close;
@end
NS_ASSUME_NONNULL_END
