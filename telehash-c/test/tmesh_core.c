#include "tmesh.h"
#include "util_sys.h"
#include "util_unix.h"
#include "unit_test.h"

// fixtures
#define A_KEY "anfpjrveyyloypswpqzlfkjpwynahohffy"
#define A_SEC "cgcsbs7yphotlb5fxls5ogy2lrc7yxbg"
#define B_KEY "amhofcnwgmolf3owg2kipr5vus7uifydsy"
#define B_SEC "ge4i7h3jln4kltngwftg2yqtjjvemerw"

tmesh_t netA = NULL, netB = NULL;
#define RXTX(a,b) (a->tx)?memcpy(b->frame,a->frame,64):memcpy(a->frame,b->frame,64)

tempo_t driver_sort(tmesh_t tm, tempo_t a, tempo_t b)
{
  if(a) return a;
  return b;
}

uint8_t scheduled = 0;
tmesh_t driver_schedule(tmesh_t tm)
{
  // start knock
  scheduled++;
  return tm;
}

tmesh_t driver_advance(tmesh_t tm, tempo_t tempo, uint8_t seed[8])
{
  // set channel and advance at based on seed
  tempo->at++;
  tempo->chan++;
  return tm;
}

tmesh_t driver_medium(tmesh_t tm, tempo_t tempo, uint8_t seed[8], uint32_t medium)
{
  tempo->driver = (void*)1; // flag for test check
  tempo->medium = medium?medium:1;
  return tm;
}

tmesh_t driver_free(tmesh_t tm, tempo_t tempo)
{
  return tm;
}

int main(int argc, char **argv)
{
  fail_unless(!e3x_init(NULL)); // random seed
  
  mesh_t meshA = mesh_new();
  fail_unless(meshA);
  lob_t keyA = lob_new();
  lob_set(keyA,"1a",A_KEY);
  lob_t secA = lob_new();
  lob_set(secA,"1a",A_SEC);
  fail_unless(!mesh_load(meshA,secA,keyA));
  mesh_on_discover(meshA,"auto",mesh_add);

  lob_t keyB = lob_new();
  lob_set(keyB,"1a",B_KEY);
  hashname_t hnB = hashname_vkeys(keyB);
  fail_unless(hnB);
  link_t linkAB = link_get(meshA,hnB);
  fail_unless(linkAB);
  
  netA = tmesh_new(meshA, "test", NULL);
  fail_unless(netA);
  
  netA->sort = driver_sort;
  netA->schedule = driver_schedule;
  netA->advance = driver_advance;
  netA->medium = driver_medium;
  netA->free = driver_free;
  
  fail_unless(netA->knock);
  fail_unless(strcmp(netA->community,"test") == 0);

  // create outgoing beacon
  fail_unless(tmesh_schedule(netA,1));
  fail_unless(netA->beacon);
  fail_unless(!netA->beacon->frames);
  fail_unless(!netA->beacon->mote);
  fail_unless(netA->beacon->medium == 1);

  // should have schedule a beacon rx
  fail_unless(scheduled == 1);
  fail_unless(netA->knock->is_active);
  fail_unless(netA->knock->tempo == netA->beacon);
  fail_unless(netA->knock->tempo->at == 2);
  fail_unless(netA->knock->tempo->chan == 1);

  mote_t moteB = tmesh_mote(netA, linkAB);
  fail_unless(moteB);
  fail_unless(moteB->link == linkAB);
  fail_unless(moteB->signal);
  fail_unless(moteB->signal->medium == 1);
  fail_unless(moteB->signal->driver == (void*)1);

  /*
  cmnty_t c = tmesh_join(netA,"qzjb5f4t","foo");
  fail_unless(c);
  fail_unless(c->medium->bin[0] == 134);
  fail_unless(c->medium->radio == devA->id);
  fail_unless(c->beacons == NULL);
  fail_unless(c->pipe->path);
  LOG("netA %.*s",c->pipe->path->head_len,c->pipe->path->head);
  fail_unless(tmesh_leave(netA,c));

  char hex[256];
  netA->last = 1;
  fail_unless(!tmesh_join(netA, "azdhpa5r", NULL));
  fail_unless((c = tmesh_join(netA, "azdhpa5n", "Public")));
  fail_unless(c->beacons);
  fail_unless(c->beacons->public);
  mote_t m = c->beacons;
  mote_t mpub = m;
  memset(m->nonce,42,8); // nonce is random, force stable for fixture testing
  LOG("nonce %s",util_hex(m->nonce,8,hex));
  fail_unless(util_cmp(hex,"2a2a2a2a2a2a2a2a") == 0);
  LOG("public at is now %lu",c->beacons->at);

  m = tmesh_link(netA, c, link);
  fail_unless(m);
  fail_unless(m->link == link);
  fail_unless(m == tmesh_link(netA, c, link));
  fail_unless(m->order == 0);
  memset(m->nonce,3,8); // nonce is random, force stable for fixture testing
  LOG("secret %s",util_hex(m->secret,32,hex));
  fail_unless(util_cmp(hex,"b7bc9e4f1f128f49a3bcef321450b996600987b129723cc7ae752d6500883c65") == 0);
  LOG("public at is now %lu",mpub->at);
  
  fail_unless(mote_reset(m));
  memset(m->nonce,0,8); // nonce is random, force stable for fixture testing
  knock_t knock = devA->knock;
  fail_unless(mote_advance(m));
  LOG("next is %lld",m->at);
  fail_unless(m->at == 8369);
  fail_unless(mote_knock(m,knock));
  fail_unless(!knock->tx);
  fail_unless(mote_advance(m));
  fail_unless(mote_advance(m));
  fail_unless(mote_advance(m));
  fail_unless(mote_advance(m));
  fail_unless(mote_knock(m,knock));
  fail_unless(knock->tx);
  LOG("next is %lld",knock->start);
  fail_unless(knock->start == 29905);
  LOG("public at is now %lu",mpub->at);
  fail_unless(mpub->at == 1);
  mpub->at = 5;

  mote_t bm = tmesh_seek(netA,c,link->id);
  memset(bm->nonce,2,8); // nonce is random, force stable for fixture testing
  mote_reset(m);
  memset(m->nonce,2,8); // nonce is random, force stable for fixture testing
  m->at = 10;
  fail_unless(tmesh_process(netA,1,1));
  fail_unless(m->at == 9);
  fail_unless(knock->mote == mpub);

  LOG("tx %d start %lld stop %lld chan %d at %lld",knock->tx,knock->start,knock->stop,knock->chan,m->at);
  fail_unless(!knock->tx);
  fail_unless(knock->start == 4);
  fail_unless(knock->stop == 4+1000);
  fail_unless(knock->chan == 27);
//  fail_unless(tmesh_knocked(netA,knock));
  LOG("public at is now %lu",c->beacons->at);
  
  fail_unless(mote_advance(m));
  LOG("seek %s",util_hex(m->nonce,8,hex));
  fail_unless(util_cmp(hex,"dc09a8ca7f5cb75e") == 0);
  fail_unless(mote_advance(m));
  LOG("at is %lu",m->at);
  fail_unless(m->at >= 6037);

  // public ping now
//  m->at = 0xffffffff;
  memset(knock,0,sizeof(struct knock_struct));
  fail_unless(tmesh_process(netA,8050,0));
  LOG("beacon at is now %lu",bm->at);
  fail_unless(knock->mote == bm);
  LOG("tx %d start %lld stop %lld chan %d",knock->tx,knock->start,knock->stop,knock->chan);
  fail_unless(!knock->tx);
  fail_unless(knock->start == 9040);
  fail_unless(knock->stop == 9040+1000);
  fail_unless(knock->chan == 19);
  
  // TEMP DISABLED
  return 0;

  // public ping tx
  memset(m->nonce,4,8); // fixture for testing
  m->order = 1;
  memset(knock,0,sizeof(struct knock_struct));
  LOG("public at is now %lu",m->at);
  fail_unless(tmesh_process(netA,20000,0));
  fail_unless(knock->mote == bm);
  LOG("tx %d start %lld stop %lld chan %d",knock->tx,knock->start,knock->stop,knock->chan);
  fail_unless(knock->ready);
  fail_unless(knock->tx);
  fail_unless(knock->start == 21128);
  fail_unless(knock->chan == 19);
  // frame would be random ciphered, but we fixed it to test
  LOG("frame %s",util_hex(knock->frame,32+8,hex)); // just the stable part
  fail_unless(util_cmp(hex,"6c265ac8d9a533a1bc7c7f49ed83ae5d32d31b4b9b76c485b182d649c91deb08a160aab63ee8212c") == 0);
  // let's preted it's an rx now
  knock->tx = 0;
  knock->stopped = knock->stop; // fake rx good
  LOG("faking rx in");
  fail_unless(!tmesh_knocked(netA,knock)); // identity crisis
  fail_unless(tmesh_process(netA,42424,0));
  LOG("tx %d start %lld stop %lld chan %d",knock->tx,knock->start,knock->stop,knock->chan);
  fail_unless(knock->stopped);
  fail_unless(knock->start == 43444);

  // leave public community
  fail_unless(tmesh_leave(netA,c));
  
  // two motes meshing
  mesh_t meshB = mesh_new();
  fail_unless(meshB);
  lob_t secB = lob_new();
  lob_set(secB,"1a",B_SEC);
  fail_unless(!mesh_load(meshB,secB,keyB));
  mesh_on_discover(meshB,"auto",mesh_add);

  hashname_t hnA = hashname_vkeys(keyA);
  fail_unless(hnA);
  link_t linkBA = link_get(meshB,hnA);
  fail_unless(linkBA);
  
  netB = tmesh_new(meshB, NULL);
  fail_unless(netB);
  cmnty_t cB = tmesh_join(netB,"qzjb5f4t","test");
  fail_unless(cB);
  fail_unless(cB->pipe->path);
  LOG("netB %s",lob_json(cB->pipe->path));

  cmnty_t cA = tmesh_join(netA,"qzjb5f4t","test");
  fail_unless(cA);
  fail_unless(cA->pipe->path);
  LOG("netA %s",lob_json(cA->pipe->path));
  
  mote_t bmBA = tmesh_seek(netB, cB, linkBA->id);
  fail_unless(bmBA);
  fail_unless(bmBA->order == 1);
  LOG("bmBA %s secret %s",hashname_short(bmBA->beacon),util_hex(bmBA->secret,32,hex));
  fail_unless(util_cmp(hex,"9a972d28dcc211d43eafdca7877bed1bbeaec30fd3740f4b787355d10423ad12") == 0);

  mote_t mBA = tmesh_link(netB, cB, linkBA);
  fail_unless(mBA);
  fail_unless(mBA->order == 1);
  LOG("mBA %s secret %s",hashname_short(mBA->link->id),util_hex(mBA->secret,32,hex));
  fail_unless(util_cmp(hex,"9a972d28dcc211d43eafdca7877bed1bbeaec30fd3740f4b787355d10423ad12") == 0);

  mote_t bmAB = tmesh_seek(netA, cA, link->id);
  fail_unless(bmAB);
  fail_unless(bmAB->order == 0);
  LOG("bmBA %s secret %s",hashname_short(bmAB->beacon),util_hex(bmAB->secret,32,hex));
  fail_unless(util_cmp(hex,"9a972d28dcc211d43eafdca7877bed1bbeaec30fd3740f4b787355d10423ad12") == 0);

  mote_t mAB = tmesh_link(netA, cA, link);
  fail_unless(mAB);
  fail_unless(mAB->order == 0);
  LOG("mAB %s secret %s",hashname_short(mAB->link->id),util_hex(mAB->secret,32,hex));
  fail_unless(util_cmp(hex,"9a972d28dcc211d43eafdca7877bed1bbeaec30fd3740f4b787355d10423ad12") == 0);
  
  knock_t knBA = devB->knock;
  knBA->ready = 0;
  memset(mBA->nonce,0,8);
  memset(bmBA->nonce,2,8);
  fail_unless(tmesh_process(netB,10,0));
  fail_unless(knBA->mote == bmBA);
  LOG("BA tx is %d chan %d at %lu",knBA->tx,knBA->chan,knBA->start);
  fail_unless(knBA->chan == 35);
  fail_unless(knBA->tx == 0);

  knock_t knAB = devA->knock;
  knAB->ready = 0;
  memset(mAB->nonce,10,8);
  memset(bmAB->nonce,15,8);
  fail_unless(tmesh_process(netA,44444,0));
  fail_unless(knAB->mote == bmAB);
  LOG("AB tx is %d chan %d at %lu nonce %s",knAB->tx,knAB->chan,knAB->start,util_hex(mAB->nonce,8,NULL));
  fail_unless(knAB->chan == 35);
  fail_unless(knAB->tx == 1);

  // fake reception, with fake cake
  LOG("process netA");
  RXTX(knAB,knBA);
  fail_unless(tmesh_knocked(netA,knAB));
  fail_unless(tmesh_process(netA,67689,0));
  fail_unless(knAB->mote == bmAB);
  fail_unless(!bmAB->chunks);

  LOG("process netB");
  RXTX(knAB,knBA);
  fail_unless(tmesh_knocked(netB,knBA));
  fail_unless(tmesh_process(netB,22000,0));

  // dummy data for sync send
  netA->pubim = hashname_im(netA->mesh->keys, hashname_id(netA->mesh->keys,netA->mesh->keys));
  netB->pubim = hashname_im(netB->mesh->keys, hashname_id(netB->mesh->keys,netB->mesh->keys));

  // back to the future
  RXTX(knAB,knBA);
  fail_unless(tmesh_knocked(netA,knAB));
  LOG("mAB %lu mBA %lu",mAB->at,mBA->at);
  while(knBA->mote != bmBA)
  {
    knBA->ready = 0;
    fail_unless(tmesh_process(netB,knBA->stop,0));
  }
  LOG("BA tx is %d chan %d at %lu nonce %s",knBA->tx,knBA->chan,knAB->start,util_hex(mBA->nonce,8,NULL));
//  fail_unless(knBA->tx == 1);

  RXTX(knAB,knBA);
  LOG("mAB %lu mBA %lu",mAB->at,mBA->at);
  fail_unless(tmesh_knocked(netB,knBA));
  while(knAB->mote != bmAB)
  {
    knAB->ready = 0;
    fail_unless(tmesh_process(netA,knAB->stop,0));
  }
  LOG("AB tx is %d chan %d at %lu nonce %s",knAB->tx,knAB->chan,knAB->start,util_hex(mAB->nonce,8,NULL));
//  fail_unless(knAB->tx == 0);

  // in sync!
  fail_unless(!mBA->chunks);
  fail_unless(!mAB->chunks);
  mAB->at = mBA->at;
  LOG("mAB %lu mBA %lu",mAB->at,mBA->at);
  fail_unless(mAB->at == mBA->at);
  
  // continue establishing link
  printf("\n\n");
  int max = 40;
  mote_reset(mAB);
  mote_reset(mBA);
  mote_reset(bmAB);
  mote_reset(bmBA);
  mAB->at = mBA->at;
  bmAB->at = bmBA->at;
  uint32_t step = mAB->at;
  while(--max > 0 && !link_up(mBA->link) && !link_up(mAB->link))
  {
    printf("\n\n%d %u\n",max,step);

    tmesh_process(netA,step,0);
    tmesh_process(netB,step,0);

    LOG("AB %d %d/%d BA %d %d/%d",knAB->tx,knAB->start,knAB->stop,knBA->tx,knBA->start,knBA->stop);
    if(knAB->stop > step) step = knAB->stop;
    if(knBA->stop > step) step = knBA->stop;

    if(knAB->chan == knBA->chan)
    {
      printf("~~~~RXTX %u\n",step);
      RXTX(knAB,knBA);
      knAB->stopped = knAB->stop;
      knBA->stopped = knBA->stop;
    }else{
      knAB->err = knBA->err = 1;
    }

    tmesh_knocked(netA,knAB);
    tmesh_knocked(netB,knBA);

  }
  LOG("TODO linked by %d",max);
//  fail_unless(max);
*/
  return 0;
}

