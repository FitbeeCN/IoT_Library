var hashname = require('hashname');
var base32 = hashname.base32;
var urilib = require('./util/uri');
var events = require('events');
var util   = require('util');
var lob    = require('lob-enc');
var stringify = require('json-stable-stringify');
var loadMeshJSON = require('./util/json').loadMeshJSON;
var log = require("./util/log")("Link");

 util.inherits(TLink, events.EventEmitter);

 // namespace as TLink to avoid js keyword collisions
 module.exports = TLink;


 /**
  * @class TLink - Telehash Link, a container for one or more @pipes (path wrappers). Most link methods are added by
  * either bundled or third party extensions. This class mostly provides the interface by which extension functionality is
  * added to a link (either directly or by adding an extension to the parent mesh)
  * https://github.com/telehash/telehash.org/blob/master/v3/link.md
  * @constructor
  * @param {meshArgs} args - a hash of options for mesh initialization
  * @param {function} callback - receives the link as the second argument or any error as the first
  */
 function TLink (mesh, args, log){

   if (!(this instanceof TLink))
     return new TLink(mesh, args);

   //set properties
   this._exchange = mesh.self.exchange;
   this._mesh     = mesh;
   this.hashname  = args.hashname;
   this.isLink    = true;
   this.args      = args;
   this.down      = 'init'; //down is any error
   this.pipes     = [];
   this.seen      = {};
   this.syncedAt  = Date.now();

   // insert link into mesh
   log.debug("mesh.index add", this.hashname)

   mesh.index[this.hashname] = this;
   mesh.links.push(this);

   // new link is created, also run extensions per link
   mesh.extended.forEach(this.extend.bind(this));

   return this;
 }

TLink.ParseArguments = function parseLinkArguments(raw){
  var args = (hashname.isHashname(raw)) ? {hashname: raw}
           : (typeof raw === "string")  ? urilib.decode(raw)
           : (typeof raw === 'object')  ? raw
           : false;

  if (!args)
    return new Error('invalid args: ' + JSON.stringify(raw));

  if (!args.hashname && (raw.keys || args.keys))
    args.hashname = hashname.fromKeys(raw.keys || args.keys);

  if (!hashname.isHashname(args.hashname))
    return new Error('invalid hashname' + JSON.stringify(args));

  return args;
}

TLink.Bail = function (err, mesh, cb){
  if (!(err instanceof Error))
    err = new Error("don't connect to yourself");

  mesh.err = err;
  if (typeof cb === 'function')
    cb(err);
  return false;
};

TLink.Log = function(lg){
  log = lg;
};

// notify an extension of a link
TLink.prototype.extend = function TLink_extend(ext)
{
  if(typeof ext.link != 'function')
    return;

  log.debug('extending link with',ext.name);

  ext.link(this, function(err){
    if(err)
      log.warn('extending a link returned an error',ext.name,err);
  });
};

// sync all pipes, try to create/init exchange if we have key info
TLink.prototype.sync = function TLink_sync()
{
  // any keepalive event, sync all pipes w/ a new handshake
  log.debug('link sync keepalive' + this.hashname + (this.x?true:false));
  if(!this.x)
    return false;

  this.x.at(this.x.at()+1); // forces new


  // track sync time for per-pipe latency on responses
  this.syncedAt = Date.now();

  var pipes = this.pipes;
  var self = this;
  this.handshake()
      .then(function(handshake){
        for (var i in pipes){
          log.debug('sending sync handshake to ', pipes[i].type, pipes[i].host)
          self.seen[pipes[i].uid] = 0;
          self.x.sending(handshake, pipes[i]);
        }
      });


  return true;
};

// generate a current handshake message
TLink.prototype.handshake = function TLink_handshake()
{
  if(this.x){
    var json = {type:'link'};
    var ims = hashname.intermediates(this._mesh.keys);

    ims[this.csid] = null;

    var handshakeOptions = {
      json : json,
      body : lob.encode(ims, hashname.key(this.csid, this._mesh.keys))
    };

    return this.x.handshake(handshakeOptions);
  } else
    return Promise.resolve(false);
};

// make sure a path is added to the json and pipe created
TLink.prototype.addPath = function TLink_handshake(path, cbPath)
{
  if(path.type === 'peer' && path.hn === this.hashname)
    return log.debug('skipping peer path to self');

  this.jsonPath(path);

  var extensions = this._mesh.extended;

  for (var i in extensions){
    if(typeof extensions[i].pipe != 'function')
      continue;
    else {
      extensions[i].pipe(this, path, function addPath_cbPipe(pipe){
        this.addPipe(pipe)
        if(typeof cbPath === 'function')
          cbPath(pipe);
      }.bind(this))
    }
  }
};

// make sure the path is in the json
TLink.prototype.jsonPath = function(path)
{
  // add to json if not exact duplicate
  var pathString = stringify(path)
    , paths      = this.json.paths
    , duplicate  = paths.filter(function filterPath(oldPath){
                     return (stringify(oldPath) === pathString);
                   })[0];
  if (!duplicate)
  {
    log.debug('addPath',path);
    paths.push(path);
  }
};

TLink.prototype.setStatus = function TLink_setStatus(err){
  if(this.down === err)
    return;

  this.down = err;
  this.up = !this.down; // convenience

  log.debug('link is',this.down||'up');

  log.debug("emmitting status event to listenes", this.label,this.listeners('status'))
  this.emit('status', this.down, this);
  if (this.down)
    this.emit('down')
};

function make_status_listener(link){
  return function status_listener(){
    link.emit('status', link.down, link);
  };
}

/** set a listener for the link 'status' event, which is fired any time
 * the link status changes.
 * @param {function=} callback - called with 'init', 'down', 'up' as first argument, and the link as the second
 * @return {string} 'init', 'down', or 'up'
 */
TLink.prototype.status = function TLink_addStatusListener(cb){
    log.debug("adding status listener, current link status: ", this.down)
  if(typeof cb === 'function')
  {
    var listeners = this.listeners('status');

    for (var i in listeners)
      if (listeners[i] === cb)
        return;

    this.on('status', cb);

    if(this.down != 'init')
      process.nextTick(make_status_listener(this));

  }
  //TODO: dynamically check status when this function is called?
  return this.down;
};

TLink.prototype.removePipe = function TLink_removePipe(pipe, cb) {
  log.debug(this.hashname.substr(0,8), "removing pipe", pipe.path);
    this.pipes.splice(this.pipes.indexOf(pipe),1)
    if (this.pipes.length == 0){
      this.setStatus(true);
    }
    
}

TLink.prototype.close = function TLink_close(){
  this.pipes.forEach(function (pipe){
    this.removePipe(pipe)
  }.bind(this))
}


TLink.prototype.pipeDown = function TLink_pipeDown(pipe){
  log.debug(this.hashname.substr(0,8), "pipe down", pipe.path);
  if (!this.pipes.reduce(function(total, pipe) {
        return total + this.seen[pipe.uid]
  }.bind(this), 0)) {
    log.debug(this.hashname.substr(0,8), "all pipes are down")
    this.setStatus("all pipes are down")
  } else {
    this.setStatus()
  }
}

TLink.prototype.addPipe = function TLink_addPipe(pipe, see)
{
  var self = this;
  // add if it doesn't exist
  if(this.pipes.indexOf(pipe) < 0)
  {
    log.debug(this.hashname.substr(0,8),'adding new pipe',pipe.path);

    // all keepalives trigger link sync
    pipe.on('keepalive', this.sync.bind(this));
    pipe.on('down', this.pipeDown.bind(this))
    pipe.on('close', this.removePipe.bind(this));
    pipe.on('error', this.removePipe.bind(this));

    // add any path to json
    if(pipe.path)
      this.jsonPath(pipe.path);

    // add to all known for this link
    this.pipes.push(pipe);

    // send most recent handshake if it's not seen
    // IMPORTANT, always call pipe.send even w/ empty packets to signal intent to transport
    if(!see)
      this.handshake()
          .then(function(handshake){
            pipe.send(handshake, self, function(){});
          })
  }

  var seen = this.seen[pipe.uid];

  // whenever a pipe is seen after a sync, update it's timestamp and resort
  if(see && (!seen || seen < this.syncedAt))
  {
    seen = Date.now();
    log.debug('pipe seen latency', pipe.uid, pipe.path, seen - this.syncedAt);
    this.seen[pipe.uid] = seen;

    // always keep them in sorted order, by shortest latency or newest
    this._sortPipes();
  }

  var self = this
  // added pipe that hasn't been seen since a sync, send most recent handshake again
  if(!see && seen && seen < this.syncedAt)
    this.handshake()
        .then(function(handshake){
          self.x.sending(handshake,pipe);
        });
}

TLink.prototype._sortPipes = function TLink__sortPipes(){
  this.pipes = this.pipes.sort(function sortPipes(a,b){
    var seenA = this.seen[a.uid]||0;
    var seenB = this.seen[b.uid]||0;
    // if both seen since last sync, prefer earliest

    return (seenA >= this.syncedAt && seenB >= this.syncedAt) ? seenA - seenB
           : (seenA >= this.syncedAt)                         ? -1
           : (seenB >= this.syncedAt)                         ? 1
           : seenB - seenA;
  }.bind(this));
  log.debug('resorted, default pipe',this.pipes[0].path);
};

// use this info as a router to reach this link
TLink.prototype.router = function(router)
{
  if((router && router.isLink)){
    this.addPath({type:'peer',hn:router.hashname});
    return true;
  } else {
    log.warn('invalid link.router args, not a link');
    return false;
  }
};

TLink.prototype.setInfo = function TLink_setInfo(args){
 var mesh = this._mesh;
 // update/set json info
 this.json = loadMeshJSON(mesh,args.hashname, args);
 this.csid = hashname.match(mesh.keys, this.json.keys);
};

TLink.prototype.createExchange = function Tlink_createExchange(){
 //first check if we already have one
 if (this.x)
   return false;

 // no csid === no exchange
 if (!this.csid){
   this.x = false;
   return false;
 }

 var exchangeOpts = {
   csid  : this.csid
   , key : base32.decode(this.json.keys[this.csid])
 };

 this.x = this._exchange(exchangeOpts);
  if (this.x){
    var self = this;

    var load = this.x.load.then(function(){
      self._mesh.index[self.x.id] = self;
    })

    self._mesh.indexer = self._mesh.indexer.then(function(){return load})

    var mesh = this._mesh
      , link = this;

    function TLink_x_sending(packet, pipe)
    {
      if((packet && (pipe = pipe || link.pipes[0]))) {
        log.debug(mesh.hashname.substr(0,8),'delivering',packet.length,'to',link.hashname.substr(0,8),pipe.path.type);
        pipe.send(packet, link, function(err){
          if(err)
            log.debug('error sending packet to pipe',link.hashname,pipe.path,err);
        });
      }
      else if(!packet)
        log.debug('sending no packet',packet);
      else
        log.debug('no pipes for',link.hashname);
    }


    this.x.sending = function(packet,pipe){
      load.then(function(){
        TLink_x_sending(packet,pipe)
      })
    }
  } else
    log.debug('failed to create exchange', this.json);// add the exchange token id for routing back to this active link
};

TLink.prototype.initialize = function TLink_initialize(args){
  // if the link was created from a received packet, first continue it
  if(args.received)
    this._mesh.receive(args.received.packet, args.received.pipe);

  // set any paths given
  if(Array.isArray(args.paths))
    args.paths.forEach(this.addPath.bind(this));

  // add a pipe if specified
  if(args.pipe)
    this.addPipe(args.pipe);

  // supplement w/ paths to default routers
  var routers  = this._mesh.routers
    , hashname = this.hashname;

  for (var i in routers)
    if (hashname !== routers[i].hashname)
      this.addPath({type:'peer', hn:routers[i].hashname});

  // default router state can be passed in on args as a convenience
  if(typeof args.router == 'boolean')
    this._mesh.router(this, args.router);
}
