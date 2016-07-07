var expect = require('chai').expect;
var lob = require('lob-enc');
var Pipe = require('../../lib/pipe.class.js');

describe('pipe', function(){

  it('should export a factory', function(){
    expect(Pipe).to.be.a('function');
  });

  it('should create new', function(){
    var pipe = new Pipe('test');
    expect(pipe).to.be.an('object');
  });

  it('should call send', function(done){
    var pipe = new Pipe('test');
    pipe.onSend = function(p, cb)
    {
      expect(p).to.be.an('object');
      cb();
    }
    pipe.send({}, done);
  });

  it('should auto-cloak', function(done){
    var pipe = new Pipe('test');
    pipe.cloaked = true;
    pipe.onSend = function(p, cb)
    {
      expect(Buffer.isBuffer(p)).to.be.true;
      expect(p.length).to.be.equal(10);
      //console.log("cloaking is awesome",p.toString("hex"));
      cb();
    }
    var packet = lob.decode(new Buffer("\0\0"));
    pipe.send(packet, done);
  });

  it('should emit an event', function(done){
    var pipe = new Pipe('test');
    pipe.on('test',function(on,a){
      expect(on).to.be.true;
      expect(this).to.be.equal(pipe);
      done();
    });
    pipe.emit('test',true);
  });
  /*
  it('should keep/remove/dedup handlers', function(){
    var pipe = new Pipe('test');
    pipe.on('test',function(){ return false; });
    var truer = function(){ return true; };
    pipe.on('test',truer);
    pipe.on('test',truer);
    expect(pipe.ons['test'].length).to.be.equal(2);
    pipe.emit('test');
    expect(pipe.ons['test'].length).to.be.equal(1);
  });
  */
  it('should fire a keepalive', function(done){
    var pipe = new Pipe('test',10);
    pipe.on('keepalive',function(on){
      expect(this).to.be.equal(pipe);
      clearInterval(pipe.timer)
      done();
      return false;
    });
  });

});
