var http = require('http');
var util = require('util');
var lob = require("lob-enc");
var stream = require('stream');
var ChannelStream = require("./stream.class");

var THTP = {
 Request  : {
   //fromHTTP : THTP_Request_fromHTTP,
   toHTTP   : THTP_Request_toHTTP
 },
 Response : {
   fromHTTP : THTP_Response_fromHTTP
   //toHTTP   : THTP_Response_toHTTP
 }
};

module.exports = THTP;

var RequestSuper = (http.IncomingMessage) || stream.Readable;
var ResponseSuper = (http.ServerResponse) || stream.Writable;


util.inherits(THTP_Request_toHTTP, RequestSuper);
util.inherits(THTP_Response_fromHTTP, ResponseSuper);

function THTP_Request_toHTTP(packet,link, stream){
  // mimic http://nodejs.org/api/http.html#http_http_incomingmessage
  http.IncomingMessage.call(this);
  this.method = packet.json[':method'];
  this.url = packet.json[':path'] || "/";
  this.headers = packet.json;
  this.headers['x-hashname'] = link.hashname; // for any http handler visibility
  this.hashname = link.hashname;


  this.connection = {
    remoteAddress : link.hashname
    , cork : function(){
      //noop
    }
  };

  this.on = stream.on.bind(stream);
  this.read = stream.read.bind(stream);
  this.pipe = stream.pipe.bind(stream);
}

function THTP_Response_fromHTTP(req, link, stream){
  // mimic http://nodejs.org/api/http.html#http_http_incomingmessage
  http.ServerResponse.call(this, req);
  this.connection = {
    remoteAddress : link.hashname,
    _httpMessage : this,
    cork : function(){
      //console.log('res cork')
    }, uncork : function(){
      //console.log("uncork")
    }
  };

  var head = false;
  //this.on = stream.on.bind(stream)
  this._writeRaw = stream._write.bind(stream);
  this._write = stream._write.bind(stream);
  this.on('pipe',function(from){
    from.on('end',stream.end.bind(stream));
  });

  this.end = function THTP_RES_End(data, enc, callback){
    if (!head)
      this.writeHead(200);

    stream.end(data)
  };


  this.writeHead = function THTP_writeHead(statusCode, reasonPhrase, headers)
  {
    // don't double!
    this._header = ""
    if(head){
      //console.log('double call to thtp writeHead',this.statusCode,(new Error()).stack)
      return this;
    };

    head = true;
    // be friendly
    if(!headers && typeof reasonPhrase == 'object'){
      headers = reasonPhrase;
      reasonPhrase = false;
    } else if (!headers ){
      headers = this._headers;
    }
    this.statusCode = parseInt(statusCode) || 500;
    var json = {};
    json[':status'] = this.statusCode;
    if(reasonPhrase)
      json[':reason'] = reasonPhrase;
    if(headers)
      Object.keys(headers).forEach(function ForEachHeader(header){
        json[header.toLowerCase()] = headers[header];
      });

    stream.write(lob.encode(json,false));
    return this;
  };
}
