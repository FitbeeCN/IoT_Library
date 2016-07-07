var crypto = require("crypto")
var lob = require('lob-enc');
var handshake_collect = require("./handshake").collect;
var log = require("./log")("Receive");
var route_cacher = require("./cache.js");
var dupe = new route_cacher();

module.exports = {
  handshake : receive_handshake,
  channel   : receive_channel,
  type      : receive_type,
  decloak   : receive_decloak
}

function receive_type(packet, pipe){

  if(!packet || !pipe)
    return new Error('invalid mesh.receive args',typeof packet,typeof pipe);

  try {
    var head = JSON.parse(packet.head)
    return (head.json.type || "unknown json")
  } catch (e){
    return (packet.head.length === 0) ? "channel"
         : (packet.head.length === 1) ? "handshake"
         : "unknown binary";
  }
}

function receive_decloak(packet, pipe){
  if(!lob.isPacket(packet) && !(packet = lob.decloak(packet)))
    return new Error('invalid packet ' + typeof packet);

  if(packet.cloaked)
    pipe.cloaked = true;

  return packet;
}


function receive_channel(mesh, packet, pipe){
  var token = packet.body.slice(0,16).toString('hex');
  var link = mesh.index[token];
  if(!link)
  {
    var route = mesh.routes[token];
    if(route)
    {
      console.log('routing packet to',route.path);
      var dupid = crypto.createHash('sha256').update(packet).digest('hex');
      if(dupe(dupid)) return log.debug('dropping duplicate');
      return route.send(packet, undefined, function(){});
    }
    log.debug('dropping unknown channel packet to ',token);
    return;
  }

  var p = new Promise(function(res,rej){
    process.nextTick(function(){
      res()
    })
  })

  p.then(function(){
    return link.x.receive(packet)
  }).then(function(inner){
    // this pipe is valid, if it hasn't been seen yet, we need to resync
    if(!link.seen[pipe.uid])
    {
      log.debug('never seen pipe',pipe.uid,pipe.path)
      link.addPipe(pipe,true); // must see it first
      process.nextTick(link.sync); // full resync in the background
    }
    log.debug(inner.json.type)

    // if channel exists, handle it
    var chan = link.x.channels[inner.json.c];
    if(chan && !inner.json.type)
    {
      if(chan.state == 'gone') return log.debug('incoming channel is gone');
      return chan.receive(inner);
    }

    // new channel open, valid?
    if(inner.json.err || typeof inner.json.type != 'string') return log.debug('invalid channel open',inner.json,link.hashname);

    // do we handle this type
    log.debug('new channel open',inner.json);

    // error utility for any open handler problems
    function bouncer(err)
    {
      if(!err) return;
      var json = {err:err};
      json.c = inner.json.c;
      log.debug('bouncing open',json);
      link.x.send({json:json});
    }

    // check all the extensions for any handlers of this type
    var args = {pipe:pipe};
    for(var i=0;i<mesh.extended.length;i++)
    {
      if(typeof mesh.extended[i].open != 'object') continue;
      var handler = mesh.extended[i].open[inner.json.type];
      if(typeof handler != 'function') continue;
      // set the link to be 'this' and be done
      handler.call(link, args, inner, bouncer);
      return;
    }

    // default bounce if not handled
    return bouncer('unknown type');
  })
  .catch(function(er){
    log.debug("error", er)
  });

};

// cache incoming handshakes to aggregate them
var hcache = {};
setInterval(function hcachet(){hcache={}},60*1000);

function receive_handshake(mesh, packet, pipe){
  var link, atOld, atNew,inner;
  var token = crypto.createHash('sha256').update(packet.body.slice(0,16)).digest().slice(0,16).toString("hex");
  log.debug("receive handshake", token, mesh.routes)
  if (mesh.routes[token]){
    var route = mesh.routes[token];
    console.log('routing packet to',route.path);
    var dupid = crypto.createHash('sha256').update(packet).digest('hex');
    if(dupe(dupid)) return log.debug('dropping duplicate');
    return route.send(packet, undefined, function(){});
  } else
  mesh.self.decrypt(packet)
           .catch(function(err){
             log.debug("handshake decrypt err", err.stack)
             throw err;
           })
           .then(function(inne){
             inner = inne;
             log.debug('inner',inner.json)

             // process the handshake info to find a link

             return handshake_collect(mesh, token, inner, pipe, packet);
           }).then(function(lin){
             link = lin;
             if(!link || !link.x)
               throw new Error("No link/exchange"); // can't respond w/o an exchange

             log.debug("have link for incoming handshake", link._mesh.hashname.substr(0, 8 ))
             atOld = link.x.at();
             return link.x.sync(packet, inner);
           })
           .then(function(sync){
             atNew = link.x.at();
             log.debug('handshake sync',sync,atOld,atNew);

             // always send handshake back if not in sync
             if(!sync)
               link.handshake()
                   .then(function(handshake){
                     link.x.sending(handshake, pipe)
                   })
                   .catch(log.debug);

             // new outgoing sync
             if(atNew > atOld && (atNew - atOld > 10))
             {
               log.debug('new outgoing sync');
               link.syncedAt = Date.now();
             }

             // when in sync or we sent a newer at, trust pipe
             if(atNew >= atOld)
               link.addPipe(pipe, true);
             //log.debug("checking session", link.x.session, link.sid, link.x.session.tocken.toString('hex'))
             // if the senders token changed, we need to reset
             //console.log(link.x.session, link.sid)
             //if (link.x.session)
              //  console.log("link.x.session", link.x.session.token.toString(''))
             if(!link.x.session || (link.sid != link.x.session.token.toString('hex')))
             {
               link.sid = link.x.session.token.toString('hex');
               log.debug('new session',link.sid);
               link.x.flush(); // any existing channels can resend
               link.setStatus(); // we're up
             }
           })
           .catch(function(er){
             log.debug("handshake error: ",er.stack)
           });


}
