#include "mesh.h"
#include "tmesh.h"
#include "unit_test.h"

int main(int argc, char **argv)
{
  printf("%lu\tvoid*\n",sizeof(void*));
  printf("%lu\tmesh_t\n",sizeof(struct mesh_struct));
  printf("%lu\tlink_t\n",sizeof(struct link_struct));
  printf("%lu\tlob_t\n",sizeof(struct lob_struct));
  printf("%lu\tutil_chunk_t\n",sizeof(struct util_chunk_struct));
  printf("%lu\te3x_self_t\n",sizeof(struct e3x_self_struct));
  printf("%lu\te3x_cipher_t\n",sizeof(struct e3x_cipher_struct));
  printf("%lu\te3x_exchange_t\n",sizeof(struct e3x_exchange_struct));
  printf("%lu\tchan_t\n",sizeof(struct chan_struct));
  printf("%lu\ttmesh_t\n",sizeof(struct tmesh_struct));
  printf("%lu\tmote_t\n",sizeof(struct mote_struct));
  printf("%lu\ttempo_t\n",sizeof(struct tempo_struct));
  printf("%lu\tknock_t\n",sizeof(struct knock_struct));
  return 0;
}

