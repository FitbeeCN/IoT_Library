//
//  CryptoPPUtil.h
//  telehash
//
//  Created by Thomas Muldowney on 11/13/13.
//  Copyright (c) 2013 Telehash Foundation. All rights reserved.
//

#import <Foundation/Foundation.h>

@interface CryptoPPUtil : NSObject
+(NSData*)randomBytes:(NSInteger)length;
@end
