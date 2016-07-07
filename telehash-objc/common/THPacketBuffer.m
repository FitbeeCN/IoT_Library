//
//  THPacketBuffer.m
//  telehash
//
//  Created by Thomas Muldowney on 11/22/13.
//  Copyright (c) 2013 Telehash Foundation. All rights reserved.
//

#import "THPacketBuffer.h"
#import "THPacket.h"
#import "CLCLog.h"

@implementation  THPacketNode
+(THPacketNode*)nodeWithPacket:(THPacket *)packet;
{
    THPacketNode* node = [THPacketNode new];
    node.packet = packet;
    node.next = nil;
    node.seq = [[packet.json objectForKey:@"seq"] unsignedIntegerValue];
    return node;
}
@end


// TODO: @temas, hey man... I have been trying to grock how to do correct miss handling with this code, we need to talk
@implementation THPacketBuffer
-(id)init;
{
    self = [super init];
    if (self) {
        firstNode = nil;
    }
    return self;
}

-(void)push:(THPacket *)packet;
{
    THPacketNode* newNode = [THPacketNode nodeWithPacket:packet];
    if (!firstNode) {
        firstNode = newNode;
        tailNode = firstNode;
        return;
    } else if (firstNode.seq > newNode.seq) {
		newNode.next = firstNode;
		firstNode = newNode;
		return;
	}
    
    for (THPacketNode* curNode = firstNode; curNode != nil; curNode = curNode.next) {
        // Drop it if it matches any existing node
        if (curNode.seq == newNode.seq) return;
        // First we find a potential parent node
        if (curNode.seq < newNode.seq) {
            // If it doesn't have a child we know that's an attach point
            if (curNode.next == nil) {
                curNode.next = newNode;
                tailNode = newNode;
                return;
            // Otherwise make sure we fit in between two nodes
            } else if(curNode.next.seq > newNode.seq) {
                THPacketNode* moveNode = curNode.next;
                curNode.next = newNode;
                newNode.next = moveNode;
                return;
            }
        }
    }
    
	CLCLogDebug(@"pushed to tailNode");
    // When in doubt just attach it at the end
    tailNode.next = newNode;
}

-(THPacket*)pop;
{
    THPacket* retPacket = firstNode.packet;
    firstNode = firstNode.next;
    if (firstNode == nil) tailNode = nil;
    return retPacket;
}

-(NSUInteger)length;
{
    //XXX:  This could be cached on the parent structure if it's commonly used
    // Short circuit what we can
    if (firstNode == nil) return 0;
    if (firstNode == tailNode) return 1;
    // Count the rest
    NSUInteger total = 0;
    THPacketNode* curNode = firstNode;
    while (curNode) {
        ++total;
        curNode = curNode.next;
    }
    return total;
}

-(void)clearThrough:(NSUInteger)lastAck;
{
	CLCLogDebug(@"clearThrough: %d", lastAck);
	
    THPacketNode* curNode = firstNode;
	while (curNode && curNode.seq <= lastAck) {
        firstNode = curNode.next;
        THPacketNode* nextNode = curNode.next;
        curNode.next = nil;
        curNode.packet = nil;
        curNode = nextNode;
    }
    if (firstNode == nil) tailNode = nil;
}

-(NSUInteger)frontSeq;
{
    return firstNode.seq;
}

-(NSArray*)missingSeqFrom:(NSUInteger)firstSeq
{
    NSMutableArray* missing;
    THPacketNode* curNode = firstNode;
    THPacketNode* nextNode;
    // Find the first missing set then carry on
    for (NSUInteger i = firstSeq; i < curNode.seq; ++i) {
        if (!missing) missing = [NSMutableArray array];
        [missing addObject:[NSNumber numberWithUnsignedInteger:i]];
    }
    while (curNode) {
        nextNode = curNode.next;
        if (nextNode != nil && nextNode.seq != curNode.seq + 1) {
            if (!missing) missing = [NSMutableArray array];
            for (NSUInteger seq = curNode.seq + 1; seq < nextNode.seq; ++seq) {
                [missing addObject:[NSNumber numberWithUnsignedInteger:seq]];
            }
        }
        curNode = nextNode;
    }
    return missing;
}

-(NSArray*)packetsForMiss:(NSArray*)miss
{
	NSMutableArray* missing;
    THPacketNode* curNode = firstNode;
    THPacketNode* nextNode;
    while (curNode) {
        nextNode = curNode.next;
		
        if ([miss containsObject:[NSNumber numberWithUnsignedInteger:curNode.seq]]) {
            if (!missing) missing = [NSMutableArray array];
			[missing addObject:curNode.packet];
        }
		
        curNode = nextNode;
    }
	
    return missing;
}

-(void)forEach:(void(^)(THPacket* packet))block;
{
    THPacketNode* curNode = firstNode;
    while (curNode) {
        block(curNode.packet);
        curNode = curNode.next;
    }
}
@end
