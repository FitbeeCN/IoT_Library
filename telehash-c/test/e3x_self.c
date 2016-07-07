#include "e3x.h"
#include "util.h"
#include "unit_test.h"

int main(int argc, char **argv)
{
  fail_unless(e3x_init(NULL) == 0);
  lob_t id = e3x_generate();
  fail_unless(id);

  e3x_self_t self = e3x_self_new(id,NULL);
  fail_unless(self);
  
  int i, count = 0;
  for(i = 0; i < CS_MAX; i++)
  {
    if(!self->locals[i]) continue;
    LOG("self testing CS %d",i);
    count++;
    fail_unless(self->locals[i]);
    fail_unless(self->keys[i]);
    fail_unless(self->keys[i]->body_len);
  }

  fail_unless(count);
  e3x_self_free(self);
  lob_free(id);

  return 0;
}

