'use strict';
/**
 * dynamically wrap lob, mesh, and util functions for javascript
**/
var th = require("./thc.js");

var crypto;

var _browser = (crypto && crypto.getRandomBytes) ? true : false;
if (!_browser) crypto = require("crypto")
var rand;
if (_browser){
  rand = () => crypto.getRandomValues(new Uint8Array(1))[0]
} else {
  rand = () => crypto.randomBytes(1)[0]
}


const returntypes = {
  "_lob_json" : "json",
  "_hashname_short" : "string",
  "_lob_get" : "string"
};

const Unwrappers = {
  pointer : (_val) => _val,
  number : (_val) => _val,
  string : th.UTF8ToString,
  json : (_val) => JSON.parse(th.UTF8ToString(_val))
}

const makeVarPointer = (_arg, write, mallextra) => {
  var ptr = th._malloc(_arg.length + mallextra);
  write(_arg, ptr);
  return ptr;
}

const Wrappers = {
  number : (_val) => _val,
  function : (_fun) => th.Runtime.addFunction(_fun),
  string : (_string) => makeVarPointer(_string, th.writeStringToMemory, 1),
  object : (_buf) => (Buffer.isBuffer(_buf)) ?  makeVarPointer(_buf, th.writeArrayToMemory, 0) : _buf === null ? 0 :  new Error("only numbers, strings, functions, and buffers accepted"),
  undefined : () => 0,
  boolean : (b) => b ? 1 : 0 
}

const wrapFun = (_fun, returntype) => function() {
  let args = [];
  for(let i = 0; i < arguments.length; i++){
    let _arg = arguments[i];
    args.push( Wrappers[typeof _arg](_arg) );
  }
  return Unwrappers[returntype || "number"]( _fun.apply(null,args) );
}

Object.keys(th).filter(key => key.indexOf("_") == 0).forEach((key) => {
  let fn = key.substr(1);
  th[fn] = wrapFun(th[key], returntypes[key]);
  // globalize all the funthings!
  if(fn.indexOf("_") > 0) global[fn] = th[fn];
})

th._e3x_random(th.Runtime.addFunction(rand));

th.CALLBACK = (fun, types) => function(){
  let args = [];
  for(let i = 0; i < arguments.length; i++){
    args[i] = Unwrappers[types[i] || "number"](arguments[i]);
  }
  return _fun.apply(null,args);
}

th.BUFFER = (ptr, len) => {
  let buf = new Buffer(len);
  for (let i =0; i < len; i++){
    buf[i] = th.getValue(ptr + i)
  }
  return buf;
}

module.exports = th;