//
//  THMeshBuckets.h
//  telehash
//
//  Created by Thomas Muldowney on 1/7/14.
//  Copyright (c) 2014 Telehash Foundation. All rights reserved.
//

#import <Foundation/Foundation.h>
#import "THChannel.h"

@class THIdentity;
@class THLine;
@class THSwitch;

typedef void(^SeekCompletionBlock)(BOOL found);

@interface THMeshBuckets : NSObject<THChannelDelegate>

@property (nonatomic, assign) THSwitch* localSwitch;
@property THIdentity* localIdentity;
@property NSMutableArray* buckets;
@property NSMutableArray* pendingSeeks;

-(void)linkToIdentity:(THIdentity*)identity;
-(void)addIdentity:(THIdentity*)identity;
-(void)removeLine:(THLine*)line;
-(NSArray*)closeInBucket:(THIdentity*)seekIdentity;
-(NSArray*)nearby:(THIdentity*)seekIdentity;
-(void)seek:(THIdentity*)seekIdentity completion:(SeekCompletionBlock)completion;
@end
