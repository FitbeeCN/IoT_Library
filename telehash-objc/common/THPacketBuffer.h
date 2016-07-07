//
//  THPacketBuffer.h
//  telehash
//
//  Created by Thomas Muldowney on 11/22/13.
//  Copyright (c) 2013 Telehash Foundation. All rights reserved.
//

#import <Foundation/Foundation.h>

@class THPacket;

@interface THPacketNode : NSObject
@property THPacket* packet;
@property THPacketNode* next;
@property NSUInteger seq;
@property BOOL acked;
+(THPacketNode*)nodeWithPacket:(THPacket*)packet;
@end

@interface THPacketBuffer : NSObject {
    THPacketNode* firstNode;
    THPacketNode* tailNode;
}
@property (readonly) NSUInteger length;
-(id)init;
-(void)push:(THPacket*)packet;
-(THPacket*)pop;
-(void)clearThrough:(NSUInteger)lastAck;
-(NSUInteger)frontSeq;
-(NSArray*)packetsForMiss:(NSArray*)miss;
-(NSArray*)missingSeqFrom:(NSUInteger)firstSeq;
-(void)forEach:(void(^)(THPacket* packet))block;
@end
