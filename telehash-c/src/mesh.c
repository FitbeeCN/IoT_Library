#include "telehash.h"
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include "telehash.h"

// internally handle list of triggers active on the mesh
typedef struct on_struct
{
  char *id; // used to store in index
  
  void (*free)(mesh_t mesh); // relese resources
  void (*link)(link_t link); // when a link is created, and again when exchange is created
  link_t (*path)(link_t link, lob_t path); // convert path->pipe
  lob_t (*open)(link_t link, lob_t open); // incoming channel requests
  link_t (*discover)(mesh_t mesh, lob_t discovered); // incoming unknown hashnames
  
  struct on_struct *next;
} *on_t;
on_t on_get(mesh_t mesh, char *id);
on_t on_free(on_t on);

mesh_t mesh_new(void)
{
  mesh_t mesh;
  
  // make sure we've initialized
  if(e3x_init(NULL)) return LOG_ERROR("e3x init failed");

  if(!(mesh = malloc(sizeof (struct mesh_struct)))) return NULL;
  memset(mesh, 0, sizeof(struct mesh_struct));
  
  LOG_INFO("mesh created version %d.%d.%d",TELEHASH_VERSION_MAJOR,TELEHASH_VERSION_MINOR,TELEHASH_VERSION_PATCH);

  return mesh;
}

mesh_t mesh_free(mesh_t mesh)
{
  on_t on;
  if(!mesh) return NULL;

  // free all links first
  link_t link, next;
  for(link = mesh->links;link;link = next)
  {
    next = link->next;
    link_free(link);
  }
  
  // free any triggers first
  while(mesh->on)
  {
    on = mesh->on;
    mesh->on = on->next;
    if(on->free) on->free(mesh);
    free(on->id);
    free(on);
  }

  lob_free(mesh->keys);
  lob_free(mesh->paths);
  hashname_free(mesh->id);
  e3x_self_free(mesh->self);
  if(mesh->ipv4_local) free(mesh->ipv4_local);
  if(mesh->ipv4_public) free(mesh->ipv4_public);

  free(mesh);
  return NULL;
}

// must be called to initialize to a hashname from keys/secrets, return !0 if failed
uint8_t mesh_load(mesh_t mesh, lob_t secrets, lob_t keys)
{
  if(!mesh || !secrets || !keys) return 1;
  if(!(mesh->self = e3x_self_new(secrets, keys))) return 2;
  mesh->keys = lob_copy(keys);
  mesh->id = hashname_dup(hashname_vkeys(mesh->keys));
  LOG_INFO("mesh is %s",hashname_short(mesh->id));
  return 0;
}

// creates a new mesh identity, returns secrets
lob_t mesh_generate(mesh_t mesh)
{
  lob_t secrets;
  if(!mesh || mesh->self) return LOG_ERROR("invalid mesh");
  secrets = e3x_generate();
  if(!secrets) return LOG_ERROR("failed to generate %s",e3x_err());
  if(mesh_load(mesh, secrets, lob_linked(secrets))) return lob_free(secrets);
  return secrets;
}

// simple accessors
hashname_t mesh_id(mesh_t mesh)
{
  if(!mesh) return NULL;
  return mesh->id;
}

lob_t mesh_keys(mesh_t mesh)
{
  if(!mesh) return NULL;
  return mesh->keys;
}

// generate json of mesh keys and current paths
lob_t mesh_json(mesh_t mesh)
{
  lob_t json, paths;
  if(!mesh) return LOG_ERROR("bad args");

  json = lob_new();
  lob_set(json,"hashname",hashname_char(mesh->id));
  lob_set_raw(json,"keys",0,(char*)mesh->keys->head,mesh->keys->head_len);
  paths = lob_array(mesh->paths);
  lob_set_raw(json,"paths",0,(char*)paths->head,paths->head_len);
  lob_free(paths);
  return json;
}

// generate json for all links, returns lob list
lob_t mesh_links(mesh_t mesh)
{
  lob_t links = NULL;
  link_t link;

  for(link = mesh->links;link;link = link->next)
  {
    links = lob_push(links,link_json(link));
  }
  return links;
}

// process any channel timeouts based on the current/given time
mesh_t mesh_process(mesh_t mesh, uint32_t now)
{
  link_t link, next;
  if(!mesh || !now) return LOG("bad args");
  for(link = mesh->links;link;link = next)
  {
    next = link->next;
    link_process(link, now);
  }
  
  return mesh;
}

link_t mesh_add(mesh_t mesh, lob_t json)
{
  link_t link;
  lob_t keys, paths;
  uint8_t csid;

  if(!mesh || !json) return LOG("bad args");
  LOG("mesh add %s",lob_json(json));
  link = link_get(mesh, hashname_vchar(lob_get(json,"hashname")));
  keys = lob_get_json(json,"keys");
  paths = lob_get_array(json,"paths");
  if(!link) link = link_get_keys(mesh, keys);
  if(!link) LOG("no hashname");
  
  LOG("loading keys from %s",lob_json(keys));
  if(keys && (csid = hashname_id(mesh->keys,keys))) link_load(link, csid, keys);

  // handle any pipe/paths
  lob_t path;
  for(path=paths;path;path = lob_next(path)) mesh_path(mesh,link,path);
  
  lob_free(keys);
  lob_freeall(paths);

  return link;
}

link_t mesh_linked(mesh_t mesh, char *hn, size_t len)
{
  link_t link;
  if(!mesh || !hn) return NULL;
  if(!len) len = strlen(hn);
  
  for(link = mesh->links;link;link = link->next) if(strncmp(hashname_char(link->id),hn,len) == 0) return link;
  
  return NULL;
}

link_t mesh_linkid(mesh_t mesh, hashname_t id)
{
  link_t link;
  if(!mesh || !id) return NULL;
  
  for(link = mesh->links;link;link = link->next) if(hashname_scmp(link->id,id) == 0) return link;
  
  return NULL;
}

// remove this link, will event it down and clean up during next process()
mesh_t mesh_unlink(link_t link)
{
  if(!link) return NULL;
  link->csid = 0; // removal indicator
  return link->mesh;
}

// create our generic callback linked list entry
on_t on_get(mesh_t mesh, char *id)
{
  on_t on;
  
  if(!mesh || !id) return LOG("bad args");
  for(on = mesh->on; on; on = on->next) if(util_cmp(on->id,id) == 0) return on;

  if(!(on = malloc(sizeof (struct on_struct)))) return LOG("OOM");
  memset(on, 0, sizeof(struct on_struct));
  on->id = strdup(id);
  on->next = mesh->on;
  mesh->on = on;
  return on;
}

void mesh_on_free(mesh_t mesh, char *id, void (*free)(mesh_t mesh))
{
  on_t on = on_get(mesh, id);
  if(on) on->free = free;
}

void mesh_on_path(mesh_t mesh, char *id, link_t (*path)(link_t link, lob_t path))
{
  on_t on = on_get(mesh, id);
  if(on) on->path = path;
}

link_t mesh_path(mesh_t mesh, link_t link, lob_t path)
{
  if(!mesh || !link || !path) return NULL;

  on_t on;
  for(on = mesh->on; on; on = on->next)
  {
    if(on->path && on->path(link, path)) return link;
  }
  return LOG("no pipe for path %.*s",path->head_len,path->head);
}

void mesh_on_link(mesh_t mesh, char *id, void (*link)(link_t link))
{
  on_t on = on_get(mesh, id);
  if(on) on->link = link;
}

void mesh_link(mesh_t mesh, link_t link)
{
  // event notifications
  on_t on;
  for(on = mesh->on; on; on = on->next) if(on->link) on->link(link);
}

void mesh_on_open(mesh_t mesh, char *id, lob_t (*open)(link_t link, lob_t open))
{
  on_t on = on_get(mesh, id);
  if(on) on->open = open;
}

lob_t mesh_open(mesh_t mesh, link_t link, lob_t open)
{
  on_t on;
  for(on = mesh->on; open && on; on = on->next) if(on->open) open = on->open(link, open);
  return open;
}

void mesh_on_discover(mesh_t mesh, char *id, link_t (*discover)(mesh_t mesh, lob_t discovered))
{
  on_t on = on_get(mesh, id);
  if(on) on->discover = discover;
}

void mesh_discover(mesh_t mesh, lob_t discovered)
{
  on_t on;
  LOG("running mesh discover with %s",lob_json(discovered));
  for(on = mesh->on; on; on = on->next) if(on->discover) on->discover(mesh, discovered);
}

// process any unencrypted handshake packet
link_t mesh_receive_handshake(mesh_t mesh, lob_t handshake)
{
  uint32_t now;
  hashname_t from = NULL;
  link_t link;

  if(!mesh || !handshake) return LOG("bad args");
  if(!lob_get(handshake,"id"))
  {
    LOG("bad handshake, no id: %s",lob_json(handshake));
    lob_free(handshake);
    return NULL;
  }
  now = util_sys_seconds();
  
  // normalize handshake
  handshake->id = now; // save when we cached it
  if(!lob_get(handshake,"type")) lob_set(handshake,"type","link"); // default to link type
  if(!lob_get_uint(handshake,"at")) lob_set_uint(handshake,"at",now); // require an at
  LOG("handshake at %d id %s",now,lob_get(handshake,"id"));
  
  // validate/extend link handshakes immediately
  if(util_cmp(lob_get(handshake,"type"),"link") == 0)
  {
    // get the csid
    uint8_t csid = 0;
    lob_t outer;
    if((outer = lob_linked(handshake)))
    {
      csid = outer->head[0];
    }else if(lob_get(handshake,"csid")){
      util_unhex(lob_get(handshake,"csid"),2,&csid);
    }
    if(!csid)
    {
      LOG("bad link handshake, no csid: %s",lob_json(handshake));
      lob_free(handshake);
      return NULL;
    }
    char hexid[3] = {0};
    util_hex(&csid, 1, hexid);
      
    // get attached hashname
    lob_t tmp = lob_parse(handshake->body, handshake->body_len);
    from = hashname_vkey(tmp, csid);
    if(!from)
    {
      LOG("bad link handshake, no hashname: %s",lob_json(handshake));
      lob_free(tmp);
      lob_free(handshake);
      return NULL;
    }
    lob_set(handshake,"csid",hexid);
    lob_set(handshake,"hashname",hashname_char(from));
    lob_set_raw(handshake,hexid,2,"true",4); // intermediate format
    lob_body(handshake, tmp->body, tmp->body_len); // re-attach as raw key
    lob_free(tmp);

    // short-cut, if it's a key from an existing link, pass it on
    // TODO: using mesh_linked here is a stack issue during loopback peer test!
    if((link = mesh_linkid(mesh,from))) return link_receive_handshake(link, handshake);
    LOG("no link found for handshake from %s",hashname_char(from));

    // extend the key json to make it compatible w/ normal patterns
    tmp = lob_new();
    lob_set_base32(tmp,hexid,handshake->body,handshake->body_len);
    lob_set_raw(handshake,"keys",0,(char*)tmp->head,tmp->head_len);
    lob_free(tmp);
  }

  // tell anyone listening about the newly discovered handshake
  mesh_discover(mesh, handshake);
  
  return from == NULL ? NULL : mesh_linkid(mesh, from);
}

// processes incoming packet, it will take ownership of outer
link_t mesh_receive(mesh_t mesh, lob_t outer)
{
  lob_t inner = NULL;
  link_t link = NULL;
  char token[17] = {0};
  hashname_t id;

  if(!mesh || !outer) return LOG("bad args");
  
  LOG("mesh receiving %s to %s",outer->head_len?"handshake":"channel",hashname_short(mesh->id));

  // redirect modern routed packets
  if(outer->head_len == 5)
  {
    id = hashname_sbin(outer->head);
    link = mesh_linkid(mesh, id);
    if(!link)
    {
      LOG_WARN("unknown id for route request: %s",hashname_short(id));
      lob_free(outer);
      return NULL;
    }

    lob_t outer2 = lob_parse(outer->body,outer->body_len);
    lob_free(outer);
    LOG_INFO("route forwarding to %s len %d",hashname_short(link->id),lob_len(outer2));
    link_send(link, outer2);
    return NULL; // don't know the sender
  }

  // process handshakes
  if(outer->head_len == 1)
  {
    inner = e3x_self_decrypt(mesh->self, outer);
    if(!inner)
    {
      LOG_WARN("%02x handshake failed %s",outer->head[0],e3x_err());
      lob_free(outer);
      return NULL;
    }
    
    // couple the two together, inner->outer
    lob_link(inner,outer);

    // set the unique id string based on some of the first 16 (routing token) bytes in the body
    base32_encode(outer->body,10,token,17);
    lob_set(inner,"id",token);

    // process the handshake
    return mesh_receive_handshake(mesh, inner);
  }

  // handle channel packets
  if(outer->head_len == 0)
  {
    if(outer->body_len < 16)
    {
      LOG("packet too small %d",outer->body_len);
      lob_free(outer);
      return NULL;
    }

    for(link = mesh->links;link;link = link->next) if(link->x && memcmp(link->x->token,outer->body,8) == 0) break;

    if(!link)
    {
      LOG("no link found for token %s",util_hex(outer->body,8,NULL));
      lob_free(outer);
      return NULL;
    }
    
    inner = e3x_exchange_receive(link->x, outer);
    lob_free(outer);
    if(!inner) return LOG("channel decryption fail for link %s %s",hashname_short(link->id),e3x_err());
    
    LOG("channel packet %d bytes from %s",lob_len(inner),hashname_short(link->id));
    return link_receive(link,inner);
    
  }

  // transform incoming bare link json format into handshake for discovery
  if((inner = lob_get_json(outer,"keys")))
  {
    if((id = hashname_vkeys(inner)))
    {
      lob_set(outer,"hashname",hashname_char(id));
      lob_set_int(outer,"at",0);
      lob_set(outer,"type","link");
      LOG("bare incoming link json being discovered %s",lob_json(outer));
    }
    lob_free(inner);
  }
  
  // run everything else through discovery, usually plain handshakes
  mesh_discover(mesh, outer);
  link = mesh_linked(mesh, lob_get(outer,"hashname"), 0);
  lob_free(outer);

  return link;
}
